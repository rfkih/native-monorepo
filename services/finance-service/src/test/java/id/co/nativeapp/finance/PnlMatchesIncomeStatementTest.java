package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.domain.PnlFigures;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.register.service.RegisterCloseWriter;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression for ADR 0065 — the reported prod bug: the beranda (dashboard) "Laba bersih" differed
 * from the Laba-Rugi report because the dashboard read the {@code consolidated_pnl} accumulator,
 * which is fed only by hand-picked POS writers and misses every other GL posting (register-close
 * cash variance, QRIS/platform fees, depreciation, AR/AP, service charge…).
 *
 * <p>This test recreates the exact divergence in one period: a sale (which feeds BOTH the
 * accumulator and the GL) plus a register-close cash SHORT (selisih kas — {@code Dr
 * CASH_SHORT_EXPENSE 5700}, a GL posting the accumulator never sees). It then proves:
 *
 * <ol>
 *   <li>the OLD accumulator read ({@link PnlReader#accumulatedPnlForPeriod}) misses the short —
 *       expense 0, net = revenue only (the bug);
 *   <li>the NEW GL-derived dashboard read ({@link PnlReader#pnlForPeriod}) includes it — expense =
 *       the short, net = revenue − short (the fix);
 *   <li>the dashboard net now EQUALS the income statement net exactly (one source of truth).
 * </ol>
 *
 * <p>RLS is exercised throughout: every read/write runs inside a tenant-bound {@code
 * TenantContext.callAs} scope (or a self-binding consumer), so the GL queries and the variance
 * posting are scoped to the right {@code company_id} by the RLS aspect (rule 5).
 */
@SpringBootTest
class PnlMatchesIncomeStatementTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777771";
  private static final UUID BUSINESS = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final String PERIOD = "2026-06";
  private static final String ACTOR = "finance-test@example.co.id";
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-15T10:00:00Z");

  private static final long REVENUE = 5_000_000L;
  private static final long CASH_SHORT = 100_000L;

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private RegisterCloseWriter registerCloseWriter;
  @Autowired private PnlReader pnlReader;
  @Autowired private IncomeStatementReader incomeStatementReader;

  @Test
  void dashboardPnlIncludesGlOnlyPostingsAndEqualsTheIncomeStatement() throws Exception {
    // 1) A sale — feeds BOTH the consolidated_pnl accumulator AND the GL (Cr REVENUE 4000).
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, BUSINESS, Money.ofMinor(REVENUE, "IDR"), OCCURRED_AT));

    // 2) A register-close cash SHORT of 100k — posts ONLY to the GL (Dr CASH_SHORT_EXPENSE 5700 /
    //    Cr CASH_CLEARING 1900), never to consolidated_pnl. Identity holds: expected = float +
    // sales
    //    − refunds = 1,000,000; counted = 900,000; overShort = counted − expected = −100,000.
    RegisterSessionClosedEvent close =
        new RegisterSessionClosedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Instant.parse("2026-06-15T08:00:00Z"),
            Instant.parse("2026-06-15T12:00:00Z"),
            0L,
            1_000_000L,
            0L,
            1_000_000L,
            900_000L,
            -CASH_SHORT,
            "IDR",
            List.of(),
            null,
            1,
            null);
    boolean posted = TenantContext.callAs(TENANT, ACTOR, () -> registerCloseWriter.post(close));
    assertThat(posted).as("the register-close variance posted on first delivery").isTrue();

    // 3) The OLD accumulator read misses the GL-only short: expense 0, net = revenue only (the
    // bug).
    ConsolidatedPnl accumulator =
        TenantContext.callAs(TENANT, ACTOR, () -> pnlReader.accumulatedPnlForPeriod(PERIOD))
            .orElseThrow();
    assertThat(accumulator.expense())
        .as("accumulator never saw the register-close short")
        .isEqualTo(Money.ofMinor(0L, "IDR"));
    assertThat(accumulator.net()).isEqualTo(Money.ofMinor(REVENUE, "IDR"));

    // 4) The NEW GL-derived dashboard read includes the short: expense = 100k, net = revenue −
    // short.
    PnlFigures dashboard =
        TenantContext.callAs(TENANT, ACTOR, () -> pnlReader.pnlForPeriod(PERIOD)).orElseThrow();
    assertThat(dashboard.expense())
        .as("GL-derived dashboard reflects the register-close short")
        .isEqualTo(Money.ofMinor(CASH_SHORT, "IDR"));
    assertThat(dashboard.net()).isEqualTo(Money.ofMinor(REVENUE - CASH_SHORT, "IDR"));

    // 5) The dashboard net now EQUALS the income statement net exactly — one source of truth.
    IncomeStatementResponse income =
        TenantContext.callAs(TENANT, ACTOR, () -> incomeStatementReader.read(PERIOD)).orElseThrow();
    assertThat(dashboard.net().amountMinor())
        .as("dashboard P&L net == income statement net (ADR 0065)")
        .isEqualTo(income.netMinor());
    assertThat(dashboard.revenue().amountMinor()).isEqualTo(income.totalRevenueMinor());
    assertThat(dashboard.expense().amountMinor()).isEqualTo(income.totalExpenseMinor());

    // And the fix genuinely changed the figure — the dashboard is NOT the old accumulator net.
    assertThat(dashboard.net()).isNotEqualTo(accumulator.net());
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " outlet_revenue, org_unit_ref,"
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
