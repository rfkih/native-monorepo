package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema;
import id.co.nativeapp.employee.expense.projection.LinkedClaimTotalView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.service.ExpenseClaimPayrollLinker;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Writer-style unit tests for {@link ExpenseClaimPayrollLinker} with mocked repositories — no
 * Spring context, no database (mirrors {@code ExpenseClaimWriterTest}). Proves the chunking loop
 * (ADR 0030 §6, E5), the E5-transitional gate on {@link ExpenseClaimPayrollLinker
 * #markReimbursedAndEmit}, and the settle+emit fan-out over every linked claim.
 */
class ExpenseClaimPayrollLinkerTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "hr-admin-a@example.co.id";
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");
  private static final UUID RUN_ID = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final Instant POSTED_AT = Instant.parse("2026-07-25T09:00:00Z");

  private final ExpenseClaimRepository claimRepository = mock(ExpenseClaimRepository.class);
  private final PayslipLineRepository payslipLineRepository = mock(PayslipLineRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);

  private final ExpenseClaimPayrollLinker linker =
      new ExpenseClaimPayrollLinker(claimRepository, payslipLineRepository, outboxWriter);

  private static ExpenseClaim approvedClaim(UUID employeeId, UUID runId) {
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
    claim.setCompanyId(TENANT);
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-19T09:00:00Z"));
    if (runId != null) {
      ReflectionTestUtils.setField(claim, "reimbursementRunId", runId);
    }
    return claim;
  }

  private static PayrollRun postedRun() {
    PayrollRun run = new PayrollRun("2026-07", 1, "IDR");
    ReflectionTestUtils.setField(run, "id", RUN_ID);
    run.markPosted(POSTED_AT);
    return run;
  }

  // ---------------------------------------------------------------------
  // releaseForPeriod
  // ---------------------------------------------------------------------

  @Test
  void releaseForPeriodDelegatesToTheRepositoryAndReturnsTheCount() throws Exception {
    when(claimRepository.releaseForPeriod("2026-07")).thenReturn(3);

    int released = TenantContext.callAs(TENANT, ACTOR, () -> linker.releaseForPeriod("2026-07"));

    assertThat(released).isEqualTo(3);
    verify(claimRepository, times(1)).releaseForPeriod("2026-07");
  }

  // ---------------------------------------------------------------------
  // linkForRun — chunking (CLAUDE.md: chunk IN clauses at <=1000)
  // ---------------------------------------------------------------------

  @Test
  void linkForRunChunksMoreThanOneThousandEmployeeIdsIntoBoundedInClauses() throws Exception {
    List<UUID> employeeIds = new ArrayList<>();
    for (int i = 0; i < 2500; i++) {
      employeeIds.add(UUID.randomUUID());
    }
    // Three chunks: 1000 + 1000 + 500. Each call returns a distinct linked count to prove the
    // total is a genuine SUM, not just the last chunk's result.
    when(claimRepository.linkForRunChunk(eq(RUN_ID), anyList())).thenReturn(10, 20, 5);

    int total =
        TenantContext.callAs(
            TENANT, ACTOR, () -> linker.linkForRun(RUN_ID, "2026-07", employeeIds));

    assertThat(total).isEqualTo(35);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> chunkCaptor = ArgumentCaptor.forClass(List.class);
    verify(claimRepository, times(3)).linkForRunChunk(eq(RUN_ID), chunkCaptor.capture());
    List<List<UUID>> chunks = chunkCaptor.getAllValues();
    assertThat(chunks.get(0)).hasSize(1000);
    assertThat(chunks.get(1)).hasSize(1000);
    assertThat(chunks.get(2)).hasSize(500);
    // Every id appears in exactly one chunk, in order, with none dropped or duplicated.
    List<UUID> reassembled = new ArrayList<>();
    chunks.forEach(reassembled::addAll);
    assertThat(reassembled).isEqualTo(employeeIds);
  }

  @Test
  void linkForRunWithNoEmployeesNeverCallsTheRepository() throws Exception {
    int total =
        TenantContext.callAs(TENANT, ACTOR, () -> linker.linkForRun(RUN_ID, "2026-07", List.of()));

    assertThat(total).isZero();
    verify(claimRepository, never()).linkForRunChunk(any(), anyList());
  }

  @Test
  void linkForRunWithExactlyOneChunkCallsTheRepositoryOnce() throws Exception {
    List<UUID> employeeIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(claimRepository.linkForRunChunk(eq(RUN_ID), anyList())).thenReturn(2);

    int total =
        TenantContext.callAs(
            TENANT, ACTOR, () -> linker.linkForRun(RUN_ID, "2026-07", employeeIds));

    assertThat(total).isEqualTo(2);
    verify(claimRepository, times(1)).linkForRunChunk(any(), anyList());
  }

  // ---------------------------------------------------------------------
  // markReimbursedAndEmit — the E5-transitional gate (ADR 0030 §6)
  // ---------------------------------------------------------------------

  @Test
  void markReimbursedAndEmitRequiresAPostedAtStamp() {
    PayrollRun neverPosted = new PayrollRun("2026-07", 1, "IDR");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> linker.markReimbursedAndEmit(neverPosted)))
        .isInstanceOf(IllegalStateException.class);
    verify(claimRepository, never()).findByReimbursementRunId(any());
  }

  @Test
  void markReimbursedAndEmitNoOpsWhenTheRunCarriesNoExpenseReimbursementPayslipLine()
      throws Exception {
    // E5-TRANSITIONAL: pre-Track-P-Phase-P7, no earning_rule ever produces this component, so
    // the gate is always closed and this method must flip NOTHING.
    when(payslipLineRepository.existsByPayrollRunIdAndComponentKey(
            RUN_ID, ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT))
        .thenReturn(false);

    int settled =
        TenantContext.callAs(TENANT, ACTOR, () -> linker.markReimbursedAndEmit(postedRun()));

    assertThat(settled).isZero();
    verify(claimRepository, never()).findByReimbursementRunId(any());
    verify(outboxWriter, never()).write(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void markReimbursedAndEmitFlipsEveryLinkedApprovedClaimAndEmitsOnePerClaim() throws Exception {
    UUID employeeA = UUID.randomUUID();
    UUID employeeB = UUID.randomUUID();
    ExpenseClaim claimA = approvedClaim(employeeA, RUN_ID);
    ExpenseClaim claimB = approvedClaim(employeeB, RUN_ID);

    when(payslipLineRepository.existsByPayrollRunIdAndComponentKey(
            RUN_ID, ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT))
        .thenReturn(true);
    when(claimRepository.findByReimbursementRunId(RUN_ID)).thenReturn(List.of(claimA, claimB));

    int settled =
        TenantContext.callAs(TENANT, ACTOR, () -> linker.markReimbursedAndEmit(postedRun()));

    assertThat(settled).isEqualTo(2);
    assertThat(claimA.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    assertThat(claimA.getSettledAt()).isEqualTo(POSTED_AT);
    assertThat(claimB.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    verify(claimRepository, times(1)).save(claimA);
    verify(claimRepository, times(1)).save(claimB);

    ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(outboxWriter, times(2))
        .write(
            eq(ExpenseReimbursementSettledSchema.AGGREGATE_TYPE),
            any(),
            eq(ExpenseReimbursementSettledSchema.EVENT_TYPE),
            payloadCaptor.capture(),
            any(),
            eq(UUID.fromString(TENANT)),
            eq(POSTED_AT));

    GenericRecord recordA =
        AvroSerde.deserialize(
            payloadCaptor.getAllValues().get(0), ExpenseReimbursementSettledSchema.schema());
    assertThat(recordA.get("claim_id").toString()).isEqualTo(claimA.getId().toString());
    assertThat(recordA.get("employee_id").toString()).isEqualTo(employeeA.toString());
    assertThat(recordA.get("settlement_kind").toString())
        .isEqualTo(ExpenseReimbursementSettledSchema.KIND_PAYROLL);
    assertThat(recordA.get("payroll_run_id").toString()).isEqualTo(RUN_ID.toString());
    assertThat(recordA.get("run_seq")).isEqualTo(1);
    assertThat(recordA.get("settled_at")).isEqualTo(POSTED_AT.toEpochMilli());
  }

  @Test
  void markReimbursedAndEmitSkipsAClaimThatIsAlreadyReimbursed() throws Exception {
    // Defensive re-entrancy guard: post() only calls this once per run, but the method itself
    // never trusts a single caller for that guarantee.
    ExpenseClaim already = approvedClaim(UUID.randomUUID(), RUN_ID);
    already.settleByPayrollRun(Instant.parse("2026-07-24T09:00:00Z"));

    when(payslipLineRepository.existsByPayrollRunIdAndComponentKey(
            RUN_ID, ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT))
        .thenReturn(true);
    when(claimRepository.findByReimbursementRunId(RUN_ID)).thenReturn(List.of(already));

    int settled =
        TenantContext.callAs(TENANT, ACTOR, () -> linker.markReimbursedAndEmit(postedRun()));

    assertThat(settled).isZero();
    verify(claimRepository, never()).save(any());
    verify(outboxWriter, never()).write(any(), any(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------
  // findLinkedClaimTotalsByEmployee
  // ---------------------------------------------------------------------

  @Test
  void findLinkedClaimTotalsByEmployeeDelegatesStraightThroughToTheRepository() throws Exception {
    LinkedClaimTotalView view = mock(LinkedClaimTotalView.class);
    when(claimRepository.findLinkedClaimTotalsByEmployee(RUN_ID)).thenReturn(List.of(view));

    List<LinkedClaimTotalView> result =
        TenantContext.callAs(TENANT, ACTOR, () -> linker.findLinkedClaimTotalsByEmployee(RUN_ID));

    assertThat(result).containsExactly(view);
  }
}
