package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.domain.UnknownAccountException;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse.ActualLine;
import id.co.nativeapp.finance.budget.dto.BudgetResponse;
import id.co.nativeapp.finance.budget.service.BudgetActualReader;
import id.co.nativeapp.finance.budget.service.BudgetLineInput;
import id.co.nativeapp.finance.budget.service.BudgetReader;
import id.co.nativeapp.finance.budget.service.BudgetWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end integration test (real PostgreSQL, unprivileged {@code app_user}) for Phase 5 budgets:
 * create a budget with lines, read its detail, and compute the budget-vs-actual variance against real
 * seeded GL activity (an AR invoice credits revenue 4000; an AP bill debits expense 5000), then
 * confirm RLS isolates the budget from another tenant and an unknown account is rejected. The budget
 * mirror of the AR/AP/tax tenancy tests.
 */
@SpringBootTest
class BudgetTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-dddddddddd44";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-eeeeeeeeee55";
  private static final String ACTOR = "budget@integration.co.id";

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private BudgetWriter budgetWriter;
  @Autowired private BudgetReader budgetReader;
  @Autowired private BudgetActualReader budgetActualReader;
  @Autowired private Clock clock;

  @Test
  void createBudgetAndComputeVarianceAgainstActuals() throws Exception {
    String period = LedgerPosting.periodOf(clock.instant());

    // Seed actuals for tenant A: revenue 4000 = 10,000,000 (an issued invoice); expense 5000 =
    // 4,000,000 (a posted bill). Non-taxable so the only revenue/expense legs are 4000 / 5000.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          UUID invoice =
              invoiceWriter.createDraft(
                  customerWriter.create("Acme Sales", null, null).id(),
                  "IDR",
                  false,
                  List.of(new InvoiceLineInput("Sale", 1, 10_000_000L)));
          invoiceWriter.issue(invoice, 30);
          UUID bill =
              billWriter.createDraft(
                  vendorWriter.create("Acme Supplies", null, null).id(),
                  "IDR",
                  false,
                  List.of(new BillLineInput("Purchase", 1, 4_000_000L)));
          billWriter.post(bill, 30);
          return null;
        });

    // Budget the period: revenue target 12,000,000 (4000), expense target 5,000,000 (5000).
    UUID budgetId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () ->
                budgetWriter.create(
                    "Monthly plan",
                    period,
                    "IDR",
                    List.of(
                        new BudgetLineInput("4000", 12_000_000L),
                        new BudgetLineInput("5000", 5_000_000L))));

    BudgetResponse detail =
        TenantContext.callAs(TENANT_A, ACTOR, () -> budgetReader.detail(budgetId));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.name()).isEqualTo("Monthly plan");

    // Variance = actual − planned. Revenue actual 10,000,000 vs planned 12,000,000 → −2,000,000;
    // expense actual 4,000,000 vs planned 5,000,000 → −1,000,000.
    BudgetActualsResponse actuals =
        TenantContext.callAs(TENANT_A, ACTOR, () -> budgetActualReader.actuals(budgetId));
    assertThat(actuals.totalPlannedMinor()).isEqualTo(17_000_000L);
    assertThat(actuals.totalActualMinor()).isEqualTo(14_000_000L);
    assertThat(actuals.totalVarianceMinor()).isEqualTo(-3_000_000L);
    assertThat(lineFor(actuals, "4000")).satisfies(l -> {
      assertThat(l.actualMinor()).isEqualTo(10_000_000L);
      assertThat(l.varianceMinor()).isEqualTo(-2_000_000L);
    });
    assertThat(lineFor(actuals, "5000")).satisfies(l -> {
      assertThat(l.actualMinor()).isEqualTo(4_000_000L);
      assertThat(l.varianceMinor()).isEqualTo(-1_000_000L);
    });

    // RLS: tenant B sees no budgets and cannot read tenant A's budget (a 404, not a leak).
    List<?> bList = TenantContext.callAs(TENANT_B, ACTOR, () -> budgetReader.list());
    assertThat(bList).isEmpty();
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR, () -> budgetReader.detail(budgetId)))
        .isInstanceOf(BudgetNotFoundException.class);

    // An unknown account is rejected before the row is written.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR,
                    () ->
                        budgetWriter.create(
                            "Bad plan", period, "IDR", List.of(new BudgetLineInput("9998", 1L)))))
        .isInstanceOf(UnknownAccountException.class);

    // Delete removes it.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          budgetWriter.delete(budgetId);
          return null;
        });
    assertThat(TenantContext.callAs(TENANT_A, ACTOR, () -> budgetReader.list())).isEmpty();
  }

  private static ActualLine lineFor(BudgetActualsResponse actuals, String accountCode) {
    return actuals.lines().stream()
        .filter(l -> l.accountCode().equals(accountCode))
        .findFirst()
        .orElseThrow();
  }
}
