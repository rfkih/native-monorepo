package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers test: {@code journal_entry} and {@code journal_line} are under {@code FORCE ROW
 * LEVEL SECURITY} scoped to {@code app.current_tenant}. A journal posted under tenant A is
 * invisible to tenant B over an RLS-scoped connection.
 */
@SpringBootTest
class GlTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-ffffffffffff";
  private static final UUID BUSINESS = UUID.fromString("12345678-1234-1234-1234-123456789012");

  @Autowired private RevenuePostingService revenuePostingService;

  @Test
  void journalEntryPostedForTenantAIsInvisibleToTenantBViaRls() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T10:00:00Z");

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT_A, BUSINESS, Money.ofMinor(2_000_000L, "IDR"), occurredAt));

    // TENANT_A sees its own journal entries.
    long aCount = TenantContext.callAs(TENANT_A, "test-actor", () -> journalEntryCount(TENANT_A));
    assertThat(aCount).as("TENANT_A must see its own journal entries").isPositive();

    // TENANT_B sees zero (FORCE RLS blocks A's rows).
    long bCount = TenantContext.callAs(TENANT_B, "test-actor", () -> journalEntryCount(TENANT_B));
    assertThat(bCount).as("TENANT_B must NOT see TENANT_A's journal entries (FORCE RLS)").isZero();

    // journal_line also isolated.
    long aLineCount =
        TenantContext.callAs(TENANT_A, "test-actor", () -> journalLineCount(TENANT_A));
    assertThat(aLineCount).as("TENANT_A must see its own journal lines").isPositive();

    long bLineCount =
        TenantContext.callAs(TENANT_B, "test-actor", () -> journalLineCount(TENANT_B));
    assertThat(bLineCount)
        .as("TENANT_B must NOT see TENANT_A's journal lines (FORCE RLS)")
        .isZero();
  }

  private long journalEntryCount(String tenant) throws Exception {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM journal_entry")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long journalLineCount(String tenant) throws Exception {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM journal_line")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " payroll_run_ledger, group_ref, group_member, group_lead,"
              + " group_trial_balance, consolidation_ledger, consolidation_summary,"
              + " intercompany_match, group_membership_pending, processed_event,"
              + " outbox, within_company_close, member_group_index,"
              + " journal_line, journal_entry CASCADE");
    } catch (Exception ignored) {
      // Tables not yet created — nothing to reset.
    }
  }
}
