package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.service.BankAccountWriter;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.bank.service.StatementLineInput;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import id.co.nativeapp.finance.bank.service.StatementLineWriter;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers proof for V55 (go-live extension, bucket A of the SME review): officialises the
 * three role mappings that were still {@code uses_illustrative = TRUE} — {@code QRIS_FEE_EXPENSE}
 * (5720), {@code GRNI_CLEARING} (2050), {@code COGS} (5100).
 *
 * <p><strong>(a) Applies cleanly.</strong> Runs against the REAL Flyway-migrated schema — the
 * {@code @SpringBootTest} context applies every migration under {@code db/migration}, V55 included,
 * at container start (mirrors {@link PerpetualInventoryGlConfigTest} / {@link
 * RoleAccountResolverProvenanceIntegrationTest}); a green class run here already proves the
 * migration applies. V55 is expand-only (a new {@code role_account_map} row per role at version +
 * 1, a {@code chart_of_account} rename) with nothing dropped or edited in place, so — same
 * reasoning as V53's test header — it is trivially backward-compatible with every pre-V55
 * row/query; Flyway (community edition) has no built-in "down" migration, so "rolls back cleanly"
 * here means exactly that: no older reader can be broken by what V55 adds.
 *
 * <p><strong>(b) Resolver provenance.</strong> {@link
 * #qrisFeeExpenseResolvesToTheUnchangedAccountAndIsNoLongerIllustrative()} / GRNI / COGS below
 * resolve each role via the REAL {@link RoleAccountResolver} against the live V55 data — same
 * account code, {@code illustrative = false} now, version 2.
 *
 * <p><strong>(c) Reworded names.</strong> {@link #theTwoReworedNamesNoLongerContainIllustrative()}.
 *
 * <p><strong>(d) An existing posting is otherwise unchanged.</strong> {@link
 * #aQrisFeeReconciliationBuildsByteIdenticalLegsAndIsNoLongerBadgedProvisional()} drives the REAL
 * (Spring-wired, DB-backed {@link RoleAccountResolver}) {@link ReconciliationWriter#buildEntry} —
 * the exact same balanced-entry builder {@code ReconciliationWriter#reconcile} calls before
 * persisting — and proves a QRIS-fee reconciliation still resolves byte-identical Dr/Cr legs to
 * before V55; only the {@code uses_illustrative_rules} badge flips.
 *
 * <p><strong>(e) The V56 bugfix.</strong> {@link
 * #aQrisFeeReconciliationPersistsThePostingAndTheBankLine()} drives the REAL, persisted {@link
 * ReconciliationWriter#reconcile(UUID, ReconciliationCategory, Long)} path end-to-end — a
 * PRE-EXISTING, V55-unrelated gap meant {@code bank_statement_line}'s {@code ck_bank_line_category}
 * CHECK constraint (V29) was never widened for {@code QRIS_CLEARING} (ADR 0045 / V52), so this
 * exact call used to roll back with a CHECK violation and this test had to route around it via the
 * side-effect-free {@code buildEntry} builder instead (see (d) above, which still exercises {@code
 * buildEntry} directly and is unaffected either way). V56 (this migration's follow-up bugfix, out
 * of V55's own scope) widens the CHECK, so this now asserts the journal entry AND the bank line
 * actually persist.
 */
@SpringBootTest
class GlConfigOfficialiseV55Test extends PostgresRlsTestBase {

  private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
  private static final String TENANT = "99999999-9999-9999-9999-999999999955";
  private static final String ACTOR = "v55-v56@bank.co.id";

  @Autowired private RoleAccountResolver roleAccountResolver;
  @Autowired private ReconciliationWriter reconciliationWriter;
  @Autowired private BankAccountWriter bankAccountWriter;
  @Autowired private StatementLineWriter statementLineWriter;
  @Autowired private StatementLineReader statementLineReader;
  @Autowired private JdbcTemplate jdbcTemplate;

  // ----------------------------------------------------------------------- (b) resolver provenance

  @Test
  void qrisFeeExpenseResolvesToTheUnchangedAccountAndIsNoLongerIllustrative() {
    RoleAccountResolver.ResolvedAccount resolved =
        roleAccountResolver.resolveWithProvenance(AccountRole.QRIS_FEE_EXPENSE, NOW);

    assertThat(resolved.accountCode()).as("account code is unchanged by V55").isEqualTo("5720");
    assertThat(resolved.illustrative())
        .as(
            "V55 supersedes QRIS_FEE_EXPENSE's V52 illustrative version 1 with an official version"
                + " 2")
        .isFalse();
    assertThat(maxVersion("QRIS_FEE_EXPENSE")).isEqualTo(2);
  }

  @Test
  void grniClearingResolvesToTheUnchangedAccountAndIsNoLongerIllustrative() {
    RoleAccountResolver.ResolvedAccount resolved =
        roleAccountResolver.resolveWithProvenance(AccountRole.GRNI_CLEARING, NOW);

    assertThat(resolved.accountCode()).as("account code is unchanged by V55").isEqualTo("2050");
    assertThat(resolved.illustrative())
        .as("V55 supersedes GRNI_CLEARING's V53 illustrative version 1 with an official version 2")
        .isFalse();
    assertThat(maxVersion("GRNI_CLEARING")).isEqualTo(2);
  }

  @Test
  void cogsResolvesToTheUnchangedAccountAndIsNoLongerIllustrative() {
    RoleAccountResolver.ResolvedAccount resolved =
        roleAccountResolver.resolveWithProvenance(AccountRole.COGS, NOW);

    assertThat(resolved.accountCode()).as("account code is unchanged by V55").isEqualTo("5100");
    assertThat(resolved.illustrative())
        .as("V55 supersedes COGS's V53 illustrative version 1 with an official version 2")
        .isFalse();
    assertThat(maxVersion("COGS")).isEqualTo(2);
  }

  @Test
  void theV52AndV53IllustrativeVersionOneRowsAreKeptAsAuditTrailNotEdited() {
    // V55 must APPEND, never edit/delete — the version-1 illustrative rows stay exactly as V52/V53
    // seeded them, so the resolver's audit trail of what a pre-V55 posting used is preserved.
    for (String role : List.of("QRIS_FEE_EXPENSE", "GRNI_CLEARING", "COGS")) {
      Boolean v1Illustrative =
          jdbcTemplate.queryForObject(
              "SELECT uses_illustrative FROM role_account_map"
                  + " WHERE account_role = ? AND version = 1",
              Boolean.class,
              role);
      assertThat(v1Illustrative).as(role + " version 1 remains illustrative (untouched)").isTrue();

      Long rowCount =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM role_account_map WHERE account_role = ?", Long.class, role);
      assertThat(rowCount)
          .as(role + " has exactly the v1 + v2 rows, nothing deleted")
          .isEqualTo(2L);
    }
  }

  // --------------------------------------------------------------------------- (c) reworded names

  @Test
  void theTwoReworedNamesNoLongerContainIllustrative() {
    assertThat(nameOf("5720"))
        .as("QRIS MDR Fee Expense's illustrative parenthetical is stripped")
        .isEqualTo("QRIS MDR Fee Expense")
        .doesNotContain("ILLUSTRATIVE");
    assertThat(nameOf("2050"))
        .as("GRNI Clearing's illustrative parenthetical is stripped")
        .isEqualTo("GRNI Clearing")
        .doesNotContain("ILLUSTRATIVE");
  }

  @Test
  void account5100WasAlreadyCleanAndIsUntouchedByV55() {
    // 5100 (COGS) never carried an illustrative suffix (seeded clean by V2) — V55 must leave it
    // exactly as-is.
    assertThat(nameOf("5100")).isEqualTo("Cost of Goods Sold");
  }

  // -------------------------------------------------------------------- (d) an existing posting

  @Test
  void aQrisFeeReconciliationBuildsByteIdenticalLegsAndIsNoLongerBadgedProvisional() {
    BankStatementLine line =
        BankStatementLine.of(
            UUID.randomUUID(),
            LocalDate.parse("2026-08-18"),
            1_000_000L,
            "IDR",
            "QRIS settlement",
            "REF-QRIS-V55-1");
    UUID entryId = UUID.randomUUID();

    // The REAL ReconciliationWriter bean, wired with the REAL (DB-backed) RoleAccountResolver —
    // this is the exact builder ReconciliationWriter#reconcile calls before persisting, so it
    // exercises the live V55 role_account_map data end-to-end without touching the unrelated
    // ck_bank_line_category gap noted in the class javadoc.
    JournalEntry entry =
        reconciliationWriter.buildEntry(
            line, ReconciliationCategory.QRIS_CLEARING, NOW, entryId, 20_000L);

    List<JournalLine> lines = entry.getLines();
    assertThat(lineFor(lines, "1000").getDebitMinor())
        .as("Dr BANK (net) — unchanged by V55")
        .isEqualTo(1_000_000L);
    assertThat(lineFor(lines, "5720").getDebitMinor())
        .as("Dr QRIS_FEE_EXPENSE (fee) — unchanged account and amount")
        .isEqualTo(20_000L);
    assertThat(lineFor(lines, "1901").getCreditMinor())
        .as("Cr QRIS_CLEARING (gross = net + fee) — unchanged by V55")
        .isEqualTo(1_020_000L);
    assertThat(lines).hasSize(3);

    assertThat(entry.isUsesIllustrativeRules())
        .as(
            "the ONLY observable effect of V55: BANK, QRIS_CLEARING, and now QRIS_FEE_EXPENSE all"
                + " resolve official, so the entry is no longer badged provisional")
        .isFalse();
  }

  // ---------------------------------------------------------------------- (e) the V56 bugfix

  /**
   * RESTORED (was routed around, see class javadoc (e)): drives the REAL, persisted {@link
   * ReconciliationWriter#reconcile(UUID, ReconciliationCategory, Long)} path — create a bank
   * account, import a QRIS settlement deposit line, reconcile it against {@code QRIS_CLEARING} with
   * a fee — and proves both halves actually persist: the {@code bank_statement_line} flips to
   * {@code RECONCILED} with {@code reconciled_category = QRIS_CLEARING} and a linked journal entry,
   * and that journal entry's {@code journal_line} rows are byte-identical to {@link
   * #aQrisFeeReconciliationBuildsByteIdenticalLegsAndIsNoLongerBadgedProvisional()}'s pure-builder
   * result. Before V56 widened {@code ck_bank_line_category}, the {@code reconcile(...)} call
   * inside this test threw a CHECK violation and rolled back.
   */
  @Test
  void aQrisFeeReconciliationPersistsThePostingAndTheBankLine() throws Exception {
    StatementLineResponse reconciled =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              BankAccountResponse account =
                  bankAccountWriter.create("QRIS Settlement Account", "BCA", "9876543210", "IDR");
              List<StatementLineResponse> imported =
                  statementLineWriter.importLines(
                      account.id(),
                      List.of(
                          new StatementLineInput(
                              LocalDate.parse("2026-08-18"),
                              1_000_000L,
                              "QRIS settlement",
                              "REF-QRIS-V56-1")));
              UUID lineId = imported.get(0).id();

              // The REAL persisted path (not buildEntry) — the fix under test.
              reconciliationWriter.reconcile(lineId, ReconciliationCategory.QRIS_CLEARING, 20_000L);

              return statementLineReader.get(lineId);
            });

    assertThat(reconciled.status())
        .as("the bank line persisted RECONCILED")
        .isEqualTo("RECONCILED");
    assertThat(reconciled.reconciledCategory())
        .as(
            "reconciled_category persisted as QRIS_CLEARING — the exact value V29's CHECK used to"
                + " reject")
        .isEqualTo("QRIS_CLEARING");
    assertThat(reconciled.journalEntryId()).as("linked to its posted journal entry").isNotNull();

    // Read the persisted legs over the admin (BYPASSRLS) connection — mirrors
    // ledgerCountAsAdmin/runStateAsAdmin in PostgresRlsTestBase: a bare repository/JdbcTemplate
    // call
    // made OUTSIDE an explicit @Transactional business method never gets the RLS GUC applied (only
    // an ACTUAL @Transactional annotation trips RlsAutoApplyAspect's pointcut — Spring Data's
    // default-transactions wrapping on a raw repository call does not), so it would fail closed
    // (empty) here rather than proving anything.
    List<Leg> legs = journalLinesAsAdmin(reconciled.journalEntryId());

    assertThat(legs).hasSize(3);
    assertThat(legFor(legs, "1000").debitMinor()).as("Dr BANK (net)").isEqualTo(1_000_000L);
    assertThat(legFor(legs, "5720").debitMinor())
        .as("Dr QRIS_FEE_EXPENSE (fee)")
        .isEqualTo(20_000L);
    assertThat(legFor(legs, "1901").creditMinor())
        .as("Cr QRIS_CLEARING (gross = net + fee)")
        .isEqualTo(1_020_000L);
    long totalDebit = legs.stream().mapToLong(Leg::debitMinor).sum();
    long totalCredit = legs.stream().mapToLong(Leg::creditMinor).sum();
    assertThat(totalDebit).as("balanced entry").isEqualTo(totalCredit).isEqualTo(1_020_000L);
  }

  private static JournalLine lineFor(List<JournalLine> lines, String accountCode) {
    return lines.stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .orElseThrow();
  }

  private record Leg(String accountCode, long debitMinor, long creditMinor) {}

  private static Leg legFor(List<Leg> legs, String accountCode) {
    return legs.stream().filter(l -> l.accountCode().equals(accountCode)).findFirst().orElseThrow();
  }

  /** The {@code journal_line} rows for one entry, read over the admin (BYPASSRLS) connection. */
  private List<Leg> journalLinesAsAdmin(UUID entryId) throws SQLException {
    List<Leg> legs = new ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line"
                    + " WHERE entry_id = ? ORDER BY line_no")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          legs.add(
              new Leg(
                  rs.getString("account_code"),
                  rs.getLong("debit_minor"),
                  rs.getLong("credit_minor")));
        }
      }
    }
    return legs;
  }

  private int maxVersion(String accountRole) {
    return jdbcTemplate.queryForObject(
        "SELECT MAX(version) FROM role_account_map WHERE account_role = ?",
        Integer.class,
        accountRole);
  }

  private String nameOf(String accountCode) {
    return jdbcTemplate.queryForObject(
        "SELECT name FROM chart_of_account WHERE account_code = ?", String.class, accountCode);
  }
}
