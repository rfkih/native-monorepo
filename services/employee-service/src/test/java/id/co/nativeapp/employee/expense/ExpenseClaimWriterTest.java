package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.SelfApprovalException;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimEventRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Writer unit tests with mocked repositories — no Spring context, no database. Proves the
 * self-approval guard (including a would-be owner approver), the replay short-circuit (no second
 * mutation/outbox write), and that a fresh approve writes the outbox exactly once.
 */
class ExpenseClaimWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "manager-sub";
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");

  private final ExpenseClaimRepository claimRepository = mock(ExpenseClaimRepository.class);
  private final ExpenseCategoryRepository categoryRepository =
      mock(ExpenseCategoryRepository.class);
  private final ExpenseClaimEventRepository eventRepository =
      mock(ExpenseClaimEventRepository.class);
  private final AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T09:00:00Z"), ZoneOffset.UTC);

  private final ExpenseClaimWriter writer =
      new ExpenseClaimWriter(
          claimRepository,
          categoryRepository,
          eventRepository,
          assignmentRepository,
          employeeRepository,
          outboxWriter,
          clock);

  private static ExpenseClaim submittedClaim(UUID employeeId) {
    ExpenseClaim claim =
        new ExpenseClaim(
            employeeId,
            CATEGORY,
            ORG_UNIT,
            Money.ofMinor(250_000L, "IDR"),
            LocalDate.of(2026, 7, 15),
            "Warung Makan",
            "lunch",
            null);
    // A real row from the repository always carries company_id (NOT NULL, V9); the outbox
    // record's company_id field needs it too.
    claim.setCompanyId(TENANT);
    claim.submit();
    return claim;
  }

  @Test
  void approvingOnesOwnClaimThrowsSelfApprovalException() throws Exception {
    // The approver's OWN employee row — this is the guard the aggregate cannot enforce itself (it
    // doesn't know the caller's employee identity), so it lives on the writer. Role-agnostic: the
    // guard compares employee identity only, so this equally covers an "owner" approver.
    Employee approver =
        new Employee("Manager", PtkpStatus.TK0, "3201234567890123", "1234567890123456");
    ExpenseClaim claim = submittedClaim(approver.getId());
    when(eventRepository.findIdByClaimIdAndIdempotencyKey(claim.getId(), "k-self"))
        .thenReturn(Optional.empty());
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(approver));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.approve(claim.getId(), "looks fine", "k-self")))
        .isInstanceOf(SelfApprovalException.class);

    verify(outboxWriter, never()).write(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aReplayedApproveReturnsTheClaimUnchangedWithNoSecondOutboxWrite() throws Exception {
    UUID otherEmployee = UUID.randomUUID();
    ExpenseClaim alreadyApproved = submittedClaim(otherEmployee);
    alreadyApproved.approve("manager-sub", "ok", Instant.parse("2026-07-19T09:00:00Z"));

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(alreadyApproved.getId(), "k-replay"))
        .thenReturn(Optional.of(UUID.randomUUID()));
    when(claimRepository.findById(alreadyApproved.getId()))
        .thenReturn(Optional.of(alreadyApproved));

    ExpenseClaim result =
        TenantContext.callAs(
            TENANT, ACTOR, () -> writer.approve(alreadyApproved.getId(), "ok", "k-replay"));

    assertThat(result).isSameAs(alreadyApproved);
    assertThat(result.getStatus()).isEqualTo(ClaimStatus.APPROVED);
    verify(outboxWriter, never()).write(any(), any(), any(), any(), any(), any(), any());
    verify(eventRepository, never()).saveAndFlush(any());
    verify(claimRepository, never()).save(any());
  }

  @Test
  void aFreshApproveWritesTheOutboxExactlyOnce() throws Exception {
    UUID otherEmployee = UUID.randomUUID();
    ExpenseClaim claim = submittedClaim(otherEmployee);
    ExpenseCategory category = new ExpenseCategory("Supplies", "supplies", false);

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(claim.getId(), "k-fresh"))
        .thenReturn(Optional.empty());
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.empty());
    when(categoryRepository.findById(CATEGORY)).thenReturn(Optional.of(category));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> writer.approve(claim.getId(), "ok", "k-fresh"));

    assertThat(result.getStatus()).isEqualTo(ClaimStatus.APPROVED);
    assertThat(result.getDecidedBy()).isEqualTo(ACTOR);
    verify(outboxWriter, times(1)).write(any(), any(), any(), any(), any(), any(), any());
    verify(eventRepository, times(1)).saveAndFlush(any());
    verify(claimRepository, times(1)).save(eq(claim));
  }
}
