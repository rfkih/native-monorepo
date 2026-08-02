package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimSummaryResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
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
}
