package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimSummaryResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.OrgUnitExpenseSummaryResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.projection.OrgUnitExpenseCategoryTotalView;
import id.co.nativeapp.employee.expense.projection.OrgUnitExpenseStatusCountView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (mocked repositories, no Spring context) for {@link ExpenseClaimReader}'s pagination
 * bounding (W2, code review) and manager-list status validation (S4, code review).
 */
class ExpenseClaimReaderTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "aaaaaaaa-1111-1111-1111-111111111111";

  private final ExpenseClaimRepository claimRepository = mock(ExpenseClaimRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final ExpenseClaimReader reader =
      new ExpenseClaimReader(claimRepository, employeeRepository);

  @Test
  void forManagerNormalizesALowercaseStatusToUppercase() throws Exception {
    when(claimRepository.countForManager(eq("SUBMITTED"), eq(false), any())).thenReturn(0L);
    when(claimRepository.findForManager(eq("SUBMITTED"), eq(false), any(), anyInt(), anyLong()))
        .thenReturn(List.of());

    TenantContext.callAs(TENANT, ACTOR, () -> reader.forManager("submitted", null, null, null));

    verify(claimRepository).countForManager(eq("SUBMITTED"), eq(false), any());
  }

  @Test
  void forManagerWithAnUnknownStatusThrowsIllegalArgument() {
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> reader.forManager("bogus", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bogus");
  }

  @Test
  void forManagerWithABlankStatusAppliesNoFilter() throws Exception {
    when(claimRepository.countForManager(any(), eq(false), any())).thenReturn(0L);
    when(claimRepository.findForManager(any(), eq(false), any(), anyInt(), anyLong()))
        .thenReturn(List.of());

    PageResponse<ExpenseClaimSummaryResponse> page =
        TenantContext.callAs(TENANT, ACTOR, () -> reader.forManager("  ", null, null, null));

    assertThat(page.totalElements()).isEqualTo(0);
  }

  @Test
  void pageSizeIsCappedAtTheMaximumWhenTheCallerAsksForMore() throws Exception {
    Employee me = new Employee("Budi", PtkpStatus.TK0, "3201234567890123", "1234567890123456");
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.countMyClaims(me.getId())).thenReturn(0L);
    when(claimRepository.findMyClaims(eq(me.getId()), anyInt(), anyLong())).thenReturn(List.of());

    PageResponse<MyExpenseClaimResponse> page =
        TenantContext.callAs(TENANT, ACTOR, () -> reader.myClaims(null, 999));

    assertThat(page.size()).isEqualTo(ExpenseClaimReader.MAX_PAGE_SIZE);
    assertThat(page.page()).isEqualTo(0);
  }

  @Test
  void aNegativePageIsFlooredToZeroAndAnAbsentSizeDefaults() throws Exception {
    Employee me = new Employee("Budi", PtkpStatus.TK0, "3201234567890123", "1234567890123456");
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.countMyClaims(me.getId())).thenReturn(0L);
    when(claimRepository.findMyClaims(
            eq(me.getId()), eq(ExpenseClaimReader.DEFAULT_PAGE_SIZE), eq(0L)))
        .thenReturn(List.of());

    PageResponse<MyExpenseClaimResponse> page =
        TenantContext.callAs(TENANT, ACTOR, () -> reader.myClaims(-5, null));

    assertThat(page.page()).isEqualTo(0);
    assertThat(page.size()).isEqualTo(ExpenseClaimReader.DEFAULT_PAGE_SIZE);
  }

  // ---------------------------------------------------------------------
  // summary (Phase E8 — org-unit hub Expenses tab rollup)
  // ---------------------------------------------------------------------

  private static OrgUnitExpenseCategoryTotalView categoryRow(
      String categoryName, long totalMinor, String currency) {
    OrgUnitExpenseCategoryTotalView view = mock(OrgUnitExpenseCategoryTotalView.class);
    when(view.getCategoryName()).thenReturn(categoryName);
    when(view.getTotalMinor()).thenReturn(totalMinor);
    when(view.getCurrency()).thenReturn(currency);
    return view;
  }

  private static OrgUnitExpenseStatusCountView statusRow(String status, long count) {
    OrgUnitExpenseStatusCountView view = mock(OrgUnitExpenseStatusCountView.class);
    when(view.getStatus()).thenReturn(status);
    when(view.getCount()).thenReturn(count);
    return view;
  }

  @Test
  void summarySumsCategoryTotalsIntoTheGrandTotalAndCarriesTheirCurrency() throws Exception {
    // Build every mocked view FIRST, as separate statements — nesting a mock()/when() call
    // inside the argument list of another in-flight when(...).thenReturn(...) confuses
    // Mockito's (thread-local) "ongoing stubbing" state (UnfinishedStubbingException).
    List<OrgUnitExpenseCategoryTotalView> categories =
        List.of(categoryRow("Travel", 300_000L, "IDR"), categoryRow("Supplies", 120_000L, "IDR"));
    List<OrgUnitExpenseStatusCountView> statuses =
        List.of(statusRow("SUBMITTED", 2L), statusRow("APPROVED", 3L));
    when(claimRepository.summarizeByCategory(eq(true), any(), eq("2026-07")))
        .thenReturn(categories);
    when(claimRepository.summarizeByStatus(eq(true), any(), eq("2026-07"))).thenReturn(statuses);

    UUID unit = UUID.randomUUID();
    OrgUnitExpenseSummaryResponse summary =
        TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(List.of(unit), "2026-07"));

    assertThat(summary.approvedReimbursedTotalMinor()).isEqualTo(420_000L);
    assertThat(summary.currency()).isEqualTo("IDR");
    assertThat(summary.byCategory()).hasSize(2);
    assertThat(summary.byStatus())
        .extracting(OrgUnitExpenseSummaryResponse.StatusCount::status)
        .containsExactlyInAnyOrder("SUBMITTED", "APPROVED");
  }

  @Test
  void summaryWithNoCategoryRowsReturnsAZeroTotalAndANullCurrency() throws Exception {
    when(claimRepository.summarizeByCategory(eq(false), any(), any())).thenReturn(List.of());
    when(claimRepository.summarizeByStatus(eq(false), any(), any())).thenReturn(List.of());

    OrgUnitExpenseSummaryResponse summary =
        TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, null));

    assertThat(summary.approvedReimbursedTotalMinor()).isZero();
    assertThat(summary.currency()).isNull();
    assertThat(summary.byCategory()).isEmpty();
  }

  @Test
  void summaryWithMixedCategoryCurrenciesFailsLoudRatherThanMisSummingMoney() {
    List<OrgUnitExpenseCategoryTotalView> categories =
        List.of(categoryRow("Travel", 300_000L, "IDR"), categoryRow("Supplies", 20L, "USD"));
    when(claimRepository.summarizeByCategory(eq(false), any(), any())).thenReturn(categories);
    when(claimRepository.summarizeByStatus(eq(false), any(), any())).thenReturn(List.of());

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("currencies");
  }

  @Test
  void summaryWithAMalformedPeriodThrowsIllegalArgument() {
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, "not-a-period")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
  }

  @Test
  void summaryRejectsAnImpossibleMonth() {
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, "2026-13")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void summaryWithABlankPeriodAppliesNoFilter() throws Exception {
    when(claimRepository.summarizeByCategory(eq(false), any(), isNull())).thenReturn(List.of());
    when(claimRepository.summarizeByStatus(eq(false), any(), isNull())).thenReturn(List.of());

    TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(List.of(), "  "));

    verify(claimRepository).summarizeByCategory(eq(false), any(), isNull());
  }

  @Test
  void summaryWithNoOrgUnitsUsesTheSentinelScopeNotARealEmptyInList() throws Exception {
    List<UUID> sentinel = List.of(new UUID(0L, 0L));
    when(claimRepository.summarizeByCategory(eq(false), eq(sentinel), any())).thenReturn(List.of());
    when(claimRepository.summarizeByStatus(eq(false), eq(sentinel), any())).thenReturn(List.of());

    TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, null));

    verify(claimRepository).summarizeByCategory(eq(false), eq(sentinel), any());
    verify(claimRepository).summarizeByStatus(eq(false), eq(sentinel), any());
  }

  /**
   * (W1, E8 review) The reader passes the SAME caller-supplied {@code period} string to BOTH {@code
   * summarizeByCategory} AND {@code summarizeByStatus}, unmodified — the reader itself never
   * rewrites/derives a different value for either. The approved_at-vs-expense_date DATE DIMENSION
   * split (category totals reconcile to the GL on the approval period; status counts are an
   * operational incurred-date view) lives entirely inside the two native queries' WHERE clauses,
   * not here — this test is a plumbing/regression guard only. The real approved_at-vs- expense_date
   * SQL behaviour is proven against real Postgres in {@code
   * ExpenseClaimRlsIsolationTest#summaryReconcilesOnApprovedAtPeriodNotExpenseDateAndIsRlsScoped}
   * (mirrors {@code findForManager}'s RLS coverage).
   */
  @Test
  void summaryPassesTheIdenticalPeriodArgumentToBothTheCategoryAndStatusQueries() throws Exception {
    when(claimRepository.summarizeByCategory(eq(false), any(), eq("2026-08")))
        .thenReturn(List.of());
    when(claimRepository.summarizeByStatus(eq(false), any(), eq("2026-08"))).thenReturn(List.of());

    TenantContext.callAs(TENANT, ACTOR, () -> reader.summary(null, "2026-08"));

    verify(claimRepository).summarizeByCategory(eq(false), any(), eq("2026-08"));
    verify(claimRepository).summarizeByStatus(eq(false), any(), eq("2026-08"));
  }
}
