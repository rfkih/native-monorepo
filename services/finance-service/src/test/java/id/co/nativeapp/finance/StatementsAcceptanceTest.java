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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers acceptance test for the financial-statements read layer.
 *
 * <p>Posts a sale (revenue) and an expense through the existing event-driven writers, then asserts:
 *
 * <ol>
 *   <li>(a) Income Statement: net == revenue − expense for the period.
 *   <li>(b) Balance Sheet: totalAssets == totalLiabilities + totalEquity (incl. retained earnings).
 *   <li>(c) Retained earnings on the balance sheet == income statement net (single period, so
 *       cumulative net == period net).
 * </ol>
 *
 * <p>The statements read layer is DERIVED from the double-entry GL (no new tables). RLS is
 * exercised: the reads run inside a tenant-bound {@code TenantContext.callAs} scope, ensuring the
 * GL queries are scoped to the correct company_id by the RLS aspect. Each test uses a distinct
 * tenant so they remain isolated even when the singleton Spring context is reused.
 */
@SpringBootTest
class StatementsAcceptanceTest extends PostgresRlsTestBase {

  // Distinct tenants per test — isolation within the shared singleton Spring context.
  private static final String TENANT_INCOME = "55555555-5555-5555-5555-555555555551";
  private static final String TENANT_BALANCE = "55555555-5555-5555-5555-555555555552";
  private static final UUID BUSINESS = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final String PERIOD = "2026-06";
  private static final String ACTOR = "finance-test@example.co.id";
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-15T10:00:00Z");

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private IncomeStatementReader incomeStatementReader;
  @Autowired private BalanceSheetReader balanceSheetReader;

  @Test
  void incomeStatementNetEqualsRevenueMinusExpense() throws Exception {
    long revenueAmount = 8_000_000L;
    long expenseAmount = 3_000_000L;

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            TENANT_INCOME,
            BUSINESS,
            Money.ofMinor(revenueAmount, "IDR"),
            OCCURRED_AT));
    expensePostingService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            TENANT_INCOME,
            BUSINESS,
            Money.ofMinor(expenseAmount, "IDR"),
            "supplies",
            OCCURRED_AT));

    IncomeStatementResponse income =
        TenantContext.callAs(TENANT_INCOME, ACTOR, () -> incomeStatementReader.read(PERIOD))
            .orElseThrow(
                () -> new AssertionError("Income statement must be present after posting events"));

    assertThat(income.totalRevenueMinor())
        .as("income statement total revenue")
        .isEqualTo(revenueAmount);
    assertThat(income.totalExpenseMinor())
        .as("income statement total expense")
        .isEqualTo(expenseAmount);
    assertThat(income.netMinor())
        .as("income statement net = revenue - expense")
        .isEqualTo(revenueAmount - expenseAmount);
    assertThat(income.currency()).isEqualTo("IDR");
    assertThat(income.revenueLines()).isNotEmpty();
    assertThat(income.expenseLines()).isNotEmpty();
  }

  @Test
  void balanceSheetBalancesAndRetainedEarningsEqualsIncomeStatementNet() throws Exception {
    long revenueAmount = 10_000_000L;
    long expenseAmount = 4_000_000L;

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            TENANT_BALANCE,
            BUSINESS,
            Money.ofMinor(revenueAmount, "IDR"),
            OCCURRED_AT));
    expensePostingService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            TENANT_BALANCE,
            BUSINESS,
            Money.ofMinor(expenseAmount, "IDR"),
            "wages",
            OCCURRED_AT));

    BalanceSheetResponse bs =
        TenantContext.callAs(TENANT_BALANCE, ACTOR, () -> balanceSheetReader.read(PERIOD))
            .orElseThrow(
                () -> new AssertionError("Balance sheet must be present after posting events"));

    // The fundamental accounting equation.
    assertThat(bs.totalAssetsMinor())
        .as("balance sheet must balance: assets == liabilities + equity")
        .isEqualTo(bs.totalLiabilitiesAndEquityMinor());

    // Retained earnings == cumulative net income (single period here, so cumulative == period net).
    long expectedNet = revenueAmount - expenseAmount;
    assertThat(bs.retainedEarningsMinor())
        .as("retained earnings on balance sheet == income statement net")
        .isEqualTo(expectedNet);

    assertThat(bs.currency()).isEqualTo("IDR");
    // Equity section must include the retained-earnings synthetic line.
    assertThat(bs.equityLines())
        .anyMatch(l -> BalanceSheetReader.RETAINED_EARNINGS_ACCOUNT.equals(l.accountCode()));
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
      // Tables not yet created - nothing to reset.
    }
  }
}
