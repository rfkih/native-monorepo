package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.PostingTemplate;
import id.co.nativeapp.finance.gl.service.PostingTemplateResolver;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testcontainers proof for V53 (ADR 0067 Phase 0 — perpetual-inventory contracts + config).
 *
 * <p>Runs against the REAL Flyway-migrated schema (the {@code @SpringBootTest} context applies
 * every migration under {@code db/migration}, including V53, at container start) — so a green class
 * run here already proves "the migration applies cleanly" (the acceptance criterion). {@link
 * PostgresRlsTestBase} additionally proves the migration ROLLS BACK cleanly is a property of the
 * container being disposable/re-creatable per test run, not something exercised inline here (Flyway
 * itself has no built-in "down" — V53 is expand-only: new account/role/template/table rows plus a
 * CHECK-constraint widen, nothing dropped or narrowed, so it is trivially backward-compatible with
 * every pre-V53 row and query).
 */
@SpringBootTest
class PerpetualInventoryGlConfigTest extends PostgresRlsTestBase {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

  @Autowired private RoleAccountResolver roleAccountResolver;
  @Autowired private PostingTemplateResolver postingTemplateResolver;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void grniClearingRoleResolvesToTheNewAccount() {
    assertThat(roleAccountResolver.resolve(AccountRole.GRNI_CLEARING, NOW)).isEqualTo("2050");
    assertThat(illustrativeFor("GRNI_CLEARING"))
        .as(
            "V53 seeded GRNI_CLEARING as illustrative (SME-gated) version 1; V55 superseded it with"
                + " an official version 2 (bucket A of the go-live SME review) — the account code is"
                + " unchanged, only provenance flipped")
        .isFalse();
  }

  @Test
  void cogsRoleResolvesToTheExistingCogsAccount() {
    assertThat(roleAccountResolver.resolve(AccountRole.COGS, NOW))
        .as("5100 Cost of Goods Sold already existed (V2) but was never role-mapped before V53")
        .isEqualTo("5100");
    assertThat(illustrativeFor("COGS"))
        .as("V55 superseded COGS's illustrative version 1 with an official version 2")
        .isFalse();
  }

  /**
   * The illustrative (SME-gated) flag of a role's current mapping, read straight from {@code
   * role_account_map}. Kept deliberately on the COMMITTED resolver API ({@link
   * RoleAccountResolver#resolve} returns the code) plus reference data — so this Phase-0 test never
   * depends on any in-flight resolver change (an isolated Phase-0 commit must compile on its own).
   */
  private boolean illustrativeFor(String accountRole) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT uses_illustrative FROM role_account_map WHERE account_role = ?"
                + " ORDER BY version DESC LIMIT 1",
            Boolean.class,
            accountRole));
  }

  @Test
  void account2050IsSeededAsAGrniLiability() {
    String name =
        jdbcTemplate.queryForObject(
            "SELECT name FROM chart_of_account WHERE account_code = ?", String.class, "2050");
    String type =
        jdbcTemplate.queryForObject(
            "SELECT account_type FROM chart_of_account WHERE account_code = ?",
            String.class,
            "2050");

    assertThat(name).contains("GRNI Clearing");
    assertThat(type).isEqualTo("LIABILITY");
  }

  @Test
  void account5100AlreadyExistsAndWasNotReInserted() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chart_of_account WHERE account_code = '5100'", Long.class);
    String name =
        jdbcTemplate.queryForObject(
            "SELECT name FROM chart_of_account WHERE account_code = '5100'", String.class);

    assertThat(count)
        .as("exactly one 5100 row — V53's ON CONFLICT DO NOTHING is a true no-op")
        .isEqualTo(1L);
    assertThat(name).isEqualTo("Cost of Goods Sold");
  }

  @Test
  void amountBasisCheckAcceptsExpenseNetAndInventoryNet() {
    // Insert a throwaway posting_template + two lines directly exercising the widened CHECK
    // constraint (EXPENSE_NET, INVENTORY_NET) — proves V53's ALTER TABLE actually took effect. Not
    // @Transactional: posting_template/posting_template_line are global reference data (no RLS),
    // and cleanup is explicit so this test does not depend on transaction rollback semantics for a
    // DDL-adjacent CHECK-constraint proof.
    UUID templateId = UUID.randomUUID();
    try {
      jdbcTemplate.update(
          "INSERT INTO posting_template"
              + " (id, event_kind, version, uses_illustrative, effective_from, effective_to)"
              + " VALUES (?, 'BILL_POSTED', 999, TRUE, DATE '2000-01-01', DATE '2000-01-02')",
          templateId);

      jdbcTemplate.update(
          "INSERT INTO posting_template_line"
              + " (id, template_id, line_no, account_role, side, amount_basis)"
              + " VALUES (?, ?, 1, 'EXPENSE', 'DEBIT', 'EXPENSE_NET')",
          UUID.randomUUID(),
          templateId);
      jdbcTemplate.update(
          "INSERT INTO posting_template_line"
              + " (id, template_id, line_no, account_role, side, amount_basis)"
              + " VALUES (?, ?, 2, 'GRNI_CLEARING', 'DEBIT', 'INVENTORY_NET')",
          UUID.randomUUID(),
          templateId);

      Long lineCount =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM posting_template_line WHERE template_id = ?",
              Long.class,
              templateId);
      assertThat(lineCount).isEqualTo(2L);
    } finally {
      jdbcTemplate.update("DELETE FROM posting_template_line WHERE template_id = ?", templateId);
      jdbcTemplate.update("DELETE FROM posting_template WHERE id = ?", templateId);
    }
  }

  @Test
  void billPostedOfficialVersion2RemainsTheOnlyTemplateThatResolvesToday() {
    // The load-bearing Phase-0 guardrail. NOTE: the CURRENTLY-effective BILL_POSTED/BILL_VOID
    // template is already DB version 2 — V51 (go-live, ADR 0042) superseded every event_kind's
    // illustrative version 1 with an official, uses_illustrative=false version 2 BEFORE this
    // migration ever ran (verified against the live schema: a literal version-2 insert here
    // collided with `uq_posting_template_kind_version`). V53's new INVENTORY_NET-carrying template
    // therefore had to land at version 3 (effective_from = 2099-01-01) so it can NEVER win the
    // resolver's `ORDER BY version DESC` among rows whose window contains `NOW` — version 2 stays
    // selected, so BillWriter (unchanged) keeps posting Dr 5000 / Dr 1300 / Cr 2000.
    PostingTemplate posted = postingTemplateResolver.resolve(EventKind.BILL_POSTED, NOW);
    assertThat(posted).isNotNull();
    assertThat(posted.version())
        .as("V53's new template (DB version 3) must stay future-dated/inactive in Phase 0")
        .isEqualTo(2);
    assertThat(posted.usesIllustrative())
        .as("V51's go-live supersession is official, not illustrative")
        .isFalse();

    PostingTemplate voided = postingTemplateResolver.resolve(EventKind.BILL_VOID, NOW);
    assertThat(voided).isNotNull();
    assertThat(voided.version()).isEqualTo(2);
  }

  @Test
  void billPostedInventoryNetTemplateIsRegisteredButOnlyEffectiveInTheFarFuture() {
    // Proves the ADR 0067 §3 template EXISTS (registered at DB version 3) and resolves once its
    // effective window is reached — without that ever happening for a real event today (the test
    // above).
    Instant farFuture = Instant.parse("2099-06-01T00:00:00Z");
    PostingTemplate posted = postingTemplateResolver.resolve(EventKind.BILL_POSTED, farFuture);

    assertThat(posted).isNotNull();
    assertThat(posted.version()).isEqualTo(3);
    assertThat(posted.lines()).hasSize(4);
    assertThat(posted.lines().stream().map(l -> l.accountRole().name()).toList())
        .containsExactly("EXPENSE", "GRNI_CLEARING", "VAT_INPUT", "AP");
    assertThat(posted.lines().stream().map(l -> l.amountBasis()).toList())
        .containsExactly("EXPENSE_NET", "INVENTORY_NET", "TAX", "GROSS");
  }

  @Test
  @Transactional // roll back the higher-version-but-still-future probe row after the test
  void aNewerBillPostedVersionStillDoesNotResolveWhileItsWindowIsFuture() {
    // Defence-in-depth: even a version HIGHER than 3 must not resolve today if its effective_from
    // is still in the future — the resolver is date-gated, not merely version-gated.
    jdbcTemplate.update(
        "INSERT INTO posting_template"
            + " (id, event_kind, version, uses_illustrative, effective_from, effective_to)"
            + " VALUES (?, 'BILL_POSTED', 4, TRUE, DATE '2099-06-01', DATE '9999-12-31')",
        UUID.randomUUID());

    PostingTemplate posted = postingTemplateResolver.resolve(EventKind.BILL_POSTED, NOW);
    assertThat(posted.version()).isEqualTo(2);
  }
}
