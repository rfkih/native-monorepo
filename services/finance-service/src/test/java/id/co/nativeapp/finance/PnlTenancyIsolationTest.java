package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.expense.ExpensePostingService;
import id.co.nativeapp.finance.expense.ExpenseRecordedEvent;
import id.co.nativeapp.finance.pnl.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.PnlReader;
import id.co.nativeapp.finance.revenue.RevenuePostingService;
import id.co.nativeapp.finance.revenue.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (e) — CROSS-TENANT ISOLATION of the dimensional ledger + P&L, relying on AUTO-applied RLS.
 *
 * <p>Revenue and an expense posted under tenant A are invisible to tenant B: B's consolidated P&L
 * for the period is empty. The posting binds the event's {@code company_id} as the tenant; the read
 * ({@link PnlReader#pnlForPeriod}) carries NO {@code WHERE company_id} and never calls the
 * synchronizer by hand — only the auto-RLS aspect sets the tenant GUC on each
 * {@code @Transactional} unit of work, so a query as B sees zero (rule 5). Runs as the unprivileged
 * {@code app_user} role; {@code FORCE ROW LEVEL SECURITY} on both {@code ledger_posting} and {@code
 * consolidated_pnl} binds even that owning role.
 */
@SpringBootTest
class PnlTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_B = "viewer-b@example.co.id";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private PnlReader pnlReader;

  @Test
  void revenueAndExpensePostedUnderTenantAAreInvisibleToTenantBsPnl() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";

    // Post both a revenue and an expense for tenant A.
    assertThat(
            revenuePostingService.handle(
                new SaleRecordedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    BUSINESS,
                    Money.ofMinor(2_000_000L, "IDR"),
                    occurredAt)))
        .isTrue();
    assertThat(
            expensePostingService.handle(
                new ExpenseRecordedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    BUSINESS,
                    Money.ofMinor(500_000L, "IDR"),
                    "supplies",
                    occurredAt)))
        .isTrue();

    // A sees its full P&L.
    ConsolidatedPnl aView =
        TenantContext.callAs(
                TENANT_A, "viewer-a@example.co.id", () -> pnlReader.pnlForPeriod(period))
            .orElseThrow();
    assertThat(aView.net()).isEqualTo(Money.ofMinor(1_500_000L, "IDR"));

    // As B, the P&L for the period is empty — A's rows are invisible via RLS on the read model...
    Optional<ConsolidatedPnl> bView =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> pnlReader.pnlForPeriod(period));
    assertThat(bView).isEmpty();

    // ...and the dimensional ledger itself is RLS-scoped: B sees zero rows over its own connection.
    long bLedgerRows =
        TenantContext.callAs(TENANT_B, ACTOR_B, this::ledgerRowsVisibleToBoundTenant);
    assertThat(bLedgerRows).isZero();
  }

  /**
   * Counts ledger rows over a fresh app-role connection with B's tenant GUC bound, exercising the
   * RLS policy on {@code ledger_posting} directly (not over the BYPASSRLS admin connection).
   */
  private long ledgerRowsVisibleToBoundTenant() throws Exception {
    String tenant = TenantContext.require().companyId();
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        java.sql.Statement set = conn.createStatement()) {
      // Bind the session tenant the policy keys on (mirrors what the RLS aspect does per tx).
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM ledger_posting")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
