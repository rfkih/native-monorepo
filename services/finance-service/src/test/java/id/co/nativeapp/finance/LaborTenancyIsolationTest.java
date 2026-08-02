package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CROSS-TENANT ISOLATION of the labor ledger, the P&L, AND the payroll_run_ledger control table,
 * relying on AUTO-applied RLS (#23). A labor cost posted under tenant A is invisible to tenant B:
 * B's P&L is empty and B sees zero rows of the ledger and the run-control table over its own
 * connection. The posting binds the event's {@code company_id} as the tenant; the read carries NO
 * {@code WHERE company_id} — only the auto-RLS aspect sets the tenant GUC (rule 5). Runs as the
 * unprivileged {@code app_user}; {@code FORCE ROW LEVEL SECURITY} binds even that owning role.
 */
@SpringBootTest
class LaborTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_B = "viewer-b@example.co.id";

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PnlReader pnlReader;

  @Test
  void laborPostedUnderTenantAIsInvisibleToTenantB() throws Exception {
    String period = "2026-06";
    laborService.handle(
        new LaborCostAllocatedEvent(
            UUID.randomUUID(),
            TENANT_A,
            UUID.randomUUID(),
            1,
            "REGULAR",
            period,
            OUTLET,
            "5100-SALARY",
            Money.ofMinor(20_000_000L, "IDR"),
            false,
            false,
            java.time.Instant.parse("2026-06-30T10:00:00Z")));

    // A sees its labor expense.
    ConsolidatedPnl aView =
        TenantContext.callAs(
                TENANT_A, "viewer-a@example.co.id", () -> pnlReader.pnlForPeriod(period))
            .orElseThrow();
    assertThat(aView.expense()).isEqualTo(Money.ofMinor(20_000_000L, "IDR"));

    // B's P&L for the period is empty — A's rows are invisible via RLS.
    Optional<ConsolidatedPnl> bView =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> pnlReader.pnlForPeriod(period));
    assertThat(bView).isEmpty();

    // The ledger AND the run-control table are RLS-scoped: B sees zero rows over its own
    // connection.
    assertThat(
            TenantContext.callAs(
                TENANT_B, ACTOR_B, () -> rowsVisibleToBoundTenant("ledger_posting")))
        .isZero();
    assertThat(
            TenantContext.callAs(
                TENANT_B, ACTOR_B, () -> rowsVisibleToBoundTenant("payroll_run_ledger")))
        .isZero();
  }

  /** Counts rows of {@code table} over a fresh app-role connection with B's tenant GUC bound. */
  private long rowsVisibleToBoundTenant(String table) throws Exception {
    String tenant = TenantContext.require().companyId();
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        java.sql.Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM " + table)) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
