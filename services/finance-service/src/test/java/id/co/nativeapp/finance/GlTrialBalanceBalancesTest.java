package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
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
 * Testcontainers test: after posting a {@code SaleRecorded} and an {@code ExpenseRecorded}, the GL
 * trial balance for the period must satisfy Σdebits == Σcredits AND carry {@code
 * usesIllustrativeRules=true} (the illustrative seeds are active).
 */
@SpringBootTest
class GlTrialBalanceBalancesTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
  private static final String PERIOD = "2026-06";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private GlTrialBalanceReader glTrialBalanceReader;

  @Test
  void glTrialBalanceIsSigmaBalancedAndFlaggedIllustrative() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T10:00:00Z");

    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, BUSINESS, Money.ofMinor(3_000_000L, "IDR"), occurredAt));

    expensePostingService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(1_000_000L, "IDR"),
            "supplies",
            occurredAt));

    List<GlTrialBalanceLineView> lines =
        TenantContext.callAs(TENANT, "test-actor", () -> glTrialBalanceReader.read(PERIOD));

    assertThat(lines).isNotEmpty();

    long totalDebit = lines.stream().mapToLong(GlTrialBalanceLineView::getTotalDebitMinor).sum();
    long totalCredit = lines.stream().mapToLong(GlTrialBalanceLineView::getTotalCreditMinor).sum();
    assertThat(totalDebit)
        .as("Σdebit must equal Σcredit — the GL must be balanced")
        .isEqualTo(totalCredit);
    assertThat(totalDebit).as("total must be strictly positive").isPositive();

    boolean anyIllustrative =
        lines.stream().anyMatch(GlTrialBalanceLineView::getUsesIllustrativeRules);
    assertThat(anyIllustrative)
        .as("at least one line must carry uses_illustrative_rules=true (illustrative seeds active)")
        .isTrue();
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
