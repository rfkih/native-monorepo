package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.BalanceSheetReader;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers test — cross-tenant isolation on the financial statements read layer.
 *
 * <p>Revenue and expense posted under tenant A are invisible to tenant B's income statement and
 * balance sheet. The read carries NO manual {@code WHERE company_id}; RLS on {@code journal_entry}
 * and {@code journal_line} scopes every query to the bound company (rule 5). Runs as the
 * unprivileged {@code app_user} role; {@code FORCE ROW LEVEL SECURITY} on those tables binds even
 * the owning role.
 */
@SpringBootTest
class StatementsTenancyIsolationTest extends PostgresRlsTestBase {

  // Unique to this class — avoid collisions with GlAutoPostingIdempotencyTest (aaaa...) or
  // PnlTenancyIsolationTest (1111...). Using 7777.../8888... not used by any other test class.
  private static final String TENANT_A = "77777777-7777-7777-7777-777777777771";
  private static final String TENANT_B = "77777777-7777-7777-7777-777777777772";
  private static final UUID BUSINESS = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final String PERIOD = "2026-06";
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-10T09:00:00Z");

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private IncomeStatementReader incomeStatementReader;
  @Autowired private BalanceSheetReader balanceSheetReader;

  @Test
  void tenantADataIsInvisibleToTenantBOnIncomeStatementAndBalanceSheet() throws Exception {
    // Post revenue and expense for tenant A.
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT_A, BUSINESS, Money.ofMinor(5_000_000L, "IDR"), OCCURRED_AT));
    expensePostingService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            TENANT_A,
            BUSINESS,
            Money.ofMinor(2_000_000L, "IDR"),
            "supplies",
            OCCURRED_AT));

    // Tenant A sees a full income statement.
    Optional<IncomeStatementResponse> aIncome =
        TenantContext.callAs(
            TENANT_A, "reader-a@example.co.id", () -> incomeStatementReader.read(PERIOD));
    assertThat(aIncome).isPresent();
    assertThat(aIncome.get().totalRevenueMinor()).isEqualTo(5_000_000L);
    assertThat(aIncome.get().totalExpenseMinor()).isEqualTo(2_000_000L);
    assertThat(aIncome.get().netMinor()).isEqualTo(3_000_000L);

    // Tenant A sees a full balance sheet.
    Optional<BalanceSheetResponse> aBalance =
        TenantContext.callAs(
            TENANT_A, "reader-a@example.co.id", () -> balanceSheetReader.read(PERIOD));
    assertThat(aBalance).isPresent();
    assertThat(aBalance.get().totalAssetsMinor())
        .isEqualTo(aBalance.get().totalLiabilitiesAndEquityMinor());

    // Tenant B sees nothing (RLS blocks A's rows).
    Optional<IncomeStatementResponse> bIncome =
        TenantContext.callAs(
            TENANT_B, "reader-b@example.co.id", () -> incomeStatementReader.read(PERIOD));
    assertThat(bIncome).isEmpty();

    Optional<BalanceSheetResponse> bBalance =
        TenantContext.callAs(
            TENANT_B, "reader-b@example.co.id", () -> balanceSheetReader.read(PERIOD));
    assertThat(bBalance).isEmpty();
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " outlet_revenue,"
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
