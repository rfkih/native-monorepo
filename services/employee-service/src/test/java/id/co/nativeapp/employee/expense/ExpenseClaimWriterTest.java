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
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimEventRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

  private static ExpenseClaim approvedClaim(UUID employeeId) {
    ExpenseClaim claim = submittedClaim(employeeId);
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-19T09:00:00Z"));
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
    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            claim.getId(), "k-self", ExpenseClaimWriter.ACTION_APPROVE))
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

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            alreadyApproved.getId(), "k-replay", ExpenseClaimWriter.ACTION_APPROVE))
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

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            claim.getId(), "k-fresh", ExpenseClaimWriter.ACTION_APPROVE))
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

  // ---------------------------------------------------------------------
  // payDirect() — DIRECT reimbursement (ADR 0030 §6, Phase E4)
  // ---------------------------------------------------------------------

  @Test
  void aFreshPayDirectWritesTheOutboxExactlyOnceWithTheCorrectDirectFields() throws Exception {
    UUID otherEmployee = UUID.randomUUID();
    ExpenseClaim claim = approvedClaim(otherEmployee);

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            claim.getId(), "k-pay", ExpenseClaimWriter.ACTION_PAY_DIRECT))
        .thenReturn(Optional.empty());
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> writer.payDirect(claim.getId(), "k-pay"));

    assertThat(result.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    assertThat(result.getSettledAt()).isEqualTo(Instant.parse("2026-07-20T09:00:00Z"));

    ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(outboxWriter, times(1))
        .write(
            eq(ExpenseReimbursementSettledSchema.AGGREGATE_TYPE),
            eq(claim.getId().toString()),
            eq(ExpenseReimbursementSettledSchema.EVENT_TYPE),
            payloadCaptor.capture(),
            any(),
            eq(UUID.fromString(TENANT)),
            eq(Instant.parse("2026-07-20T09:00:00Z")));
    verify(eventRepository, times(1)).saveAndFlush(any());
    verify(claimRepository, times(1)).save(eq(claim));

    GenericRecord record =
        AvroSerde.deserialize(payloadCaptor.getValue(), ExpenseReimbursementSettledSchema.schema());
    assertThat(record.get("claim_id").toString()).isEqualTo(claim.getId().toString());
    assertThat(record.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(record.get("org_unit_id").toString()).isEqualTo(ORG_UNIT.toString());
    assertThat(record.get("employee_id").toString()).isEqualTo(otherEmployee.toString());
    assertThat(record.get("amount_minor")).isEqualTo(250_000L);
    assertThat(record.get("currency").toString()).isEqualTo("IDR");
    assertThat(record.get("settlement_kind").toString())
        .isEqualTo(ExpenseReimbursementSettledSchema.KIND_DIRECT);
    assertThat(record.get("payroll_run_id")).isNull();
    assertThat(record.get("run_seq")).isNull();
    assertThat(record.get("settled_at"))
        .isEqualTo(Instant.parse("2026-07-20T09:00:00Z").toEpochMilli());
  }

  @Test
  void aReplayedPayDirectReturnsTheClaimUnchangedWithNoSecondOutboxWrite() throws Exception {
    UUID otherEmployee = UUID.randomUUID();
    ExpenseClaim alreadyPaid = approvedClaim(otherEmployee);
    alreadyPaid.payDirect(Instant.parse("2026-07-20T09:00:00Z"));

    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            alreadyPaid.getId(), "k-replay-pay", ExpenseClaimWriter.ACTION_PAY_DIRECT))
        .thenReturn(Optional.of(UUID.randomUUID()));
    when(claimRepository.findById(alreadyPaid.getId())).thenReturn(Optional.of(alreadyPaid));

    ExpenseClaim result =
        TenantContext.callAs(
            TENANT, ACTOR, () -> writer.payDirect(alreadyPaid.getId(), "k-replay-pay"));

    assertThat(result).isSameAs(alreadyPaid);
    assertThat(result.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    verify(outboxWriter, never()).write(any(), any(), any(), any(), any(), any(), any());
    verify(eventRepository, never()).saveAndFlush(any());
    verify(claimRepository, never()).save(any());
  }

  @Test
  void isReplayedEventReadsThroughToTheEventRepository() {
    UUID claimId = UUID.randomUUID();
    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            claimId, "k", ExpenseClaimWriter.ACTION_SUBMIT))
        .thenReturn(Optional.of(UUID.randomUUID()));

    boolean result = writer.isReplayedEvent(claimId, "k", ExpenseClaimWriter.ACTION_SUBMIT);

    assertThat(result).isTrue();
  }

  @Test
  void isReplayedEventIsFalseWhenNoEventRowExistsForThisActionSpecifically() {
    // The S1/S2 scenario: a row exists for the SAME (claim, key) but a DIFFERENT action.
    UUID claimId = UUID.randomUUID();
    when(eventRepository.findIdByClaimIdAndIdempotencyKey(
            claimId, "k", ExpenseClaimWriter.ACTION_CANCEL))
        .thenReturn(Optional.empty());

    boolean result = writer.isReplayedEvent(claimId, "k", ExpenseClaimWriter.ACTION_CANCEL);

    assertThat(result).isFalse();
  }

  // ---------------------------------------------------------------------
  // W1 (code review): the currency-establishment check-then-act race
  // ---------------------------------------------------------------------

  private static CreateClaimCommand createCommand() {
    return new CreateClaimCommand(
        CATEGORY, 250_000L, "IDR", LocalDate.of(2026, 7, 15), "Warung Makan", "lunch", null);
  }

  private Employee stubOwnEmployeeAndCategory() {
    Employee me = new Employee("Budi", PtkpStatus.TK0, "3201234567890123", "1234567890123456");
    ExpenseCategory category = new ExpenseCategory("Supplies", "supplies", false);
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(categoryRepository.findById(CATEGORY)).thenReturn(Optional.of(category));
    when(assignmentRepository.findByEmployeeId(me.getId())).thenReturn(List.of());
    when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    return me;
  }

  @Test
  void createNeverLocksWhenACurrencyIsAlreadyEstablished() throws Exception {
    stubOwnEmployeeAndCategory();
    when(claimRepository.findAnyCurrency()).thenReturn(Optional.of("IDR"));

    TenantContext.callAs(TENANT, ACTOR, () -> writer.create(createCommand()));

    verify(claimRepository, never()).lockCurrencyEstablishment(any());
  }

  @Test
  void createLocksAndReProbesWhenNoCurrencyIsEstablishedYet() throws Exception {
    stubOwnEmployeeAndCategory();
    // Empty both times — this really is the first claim; it establishes IDR without conflict.
    when(claimRepository.findAnyCurrency()).thenReturn(Optional.empty());

    ExpenseClaim result = TenantContext.callAs(TENANT, ACTOR, () -> writer.create(createCommand()));

    assertThat(result).isNotNull();
    verify(claimRepository, times(1))
        .lockCurrencyEstablishment(eq("expense_claim_currency:" + TENANT));
    verify(claimRepository, times(2)).findAnyCurrency();
    verify(claimRepository, times(1)).save(any());
  }

  @Test
  void createRejectsAMismatchDiscoveredOnlyAfterTheLockReProbe() throws Exception {
    stubOwnEmployeeAndCategory();
    // First probe empty (no established currency yet); while this call waits for/holds the lock,
    // a concurrent racer is modeled as having ALREADY established USD, so the RE-PROBE under the
    // lock now sees it — proving the re-probe result (not the stale first probe) governs.
    when(claimRepository.findAnyCurrency()).thenReturn(Optional.empty(), Optional.of("USD"));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> writer.create(createCommand())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("USD");

    verify(claimRepository, times(1)).lockCurrencyEstablishment(any());
    // The mismatch is caught before anything is persisted.
    verify(claimRepository, never()).save(any());
  }
}
