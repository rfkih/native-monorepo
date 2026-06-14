package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.mapping.GlAccountResolution;
import id.co.nativeapp.finance.mapping.GlAccountResolver;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (c) — the EFFECTIVE-DATED mapping change: an event dated before vs after a rule-version
 * boundary resolves to the RIGHT account.
 *
 * <p>A new {@code gl_hint} ({@code rent}) gets two {@code mapping_rule} versions: version 1
 * effective up to {@code 2026-05-31} → Cost of Goods Sold ({@code 5100}); version 2 effective from
 * {@code 2026-06-01} (open-ended via the {@code 9999-12-31} sentinel) → Utilities ({@code 5300}).
 * An expense dated in May resolves to {@code 5100}; one dated in June (and later) resolves to
 * {@code 5300}. This proves the resolver selects the version whose effective window contains the
 * event's {@code occurred_at} date.
 *
 * <p>The custom rules are inserted over the admin connection (the {@code mapping_rule} reference
 * table is global, not RLS) and removed in {@code @AfterEach} so the shared seeded chart is left
 * intact for other tests.
 */
@SpringBootTest
class MappingRuleEffectiveDatingTest extends PostgresRlsTestBase {

  private static final String HINT = "rent";

  @Autowired private GlAccountResolver resolver;

  @AfterEach
  void removeCustomRules() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("DELETE FROM mapping_rule WHERE gl_hint = '" + HINT + "'");
    }
  }

  @Test
  void anExpenseBeforeAndAfterARuleVersionBoundaryResolvesToTheRightAccount() throws Exception {
    seedVersionedRentRules();

    // Dated in May 2026 — before the boundary — resolves to version 1's account (5100).
    GlAccountResolution may = resolver.resolveExpense(HINT, Instant.parse("2026-05-15T10:00:00Z"));
    assertThat(may.mapped()).isTrue();
    assertThat(may.accountCode()).isEqualTo("5100");

    // Dated in June 2026 — on/after the boundary — resolves to version 2's account (5300).
    GlAccountResolution june = resolver.resolveExpense(HINT, Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(june.mapped()).isTrue();
    assertThat(june.accountCode()).isEqualTo("5300");

    // A later date stays on version 2.
    GlAccountResolution later =
        resolver.resolveExpense(HINT, Instant.parse("2026-09-30T23:59:59Z"));
    assertThat(later.accountCode()).isEqualTo("5300");
  }

  private void seedVersionedRentRules() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      // Version 1: effective up to 2026-05-31 -> 5100.
      st.execute(
          "INSERT INTO mapping_rule (id, posting_type, gl_hint, version, gl_account_code,"
              + " effective_from, effective_to) VALUES (gen_random_uuid(), 'EXPENSE', '"
              + HINT
              + "', 1, '5100', DATE '2000-01-01', DATE '2026-05-31')");
      // Version 2: effective from 2026-06-01, open-ended -> 5300.
      st.execute(
          "INSERT INTO mapping_rule (id, posting_type, gl_hint, version, gl_account_code,"
              + " effective_from, effective_to) VALUES (gen_random_uuid(), 'EXPENSE', '"
              + HINT
              + "', 2, '5300', DATE '2026-06-01', DATE '9999-12-31')");
    }
  }
}
