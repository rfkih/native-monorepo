package id.co.nativeapp.finance.empexpense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pure-unit proofs for {@link ExpenseClaimPostingService} — every writer is mocked (no Spring / no
 * Testcontainers). Locks the settle-once conflict-recovery branch (ADR 0030 §7): a concurrent racer
 * for the SAME claim (a DIFFERENT event id — the payroll-supersession re-emission) trips the
 * settle-once guard's UNIQUE constraint, and the service recovers with a separate-transaction
 * re-check rather than propagating the failure, mirroring {@code
 * ExpenseClaimServiceConflictRecoveryTest} (employee-service) but resolving to a no-op instead of a
 * translated conflict.
 */
class ExpenseClaimPostingServiceTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID CLAIM_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID ORG_UNIT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE_ID = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  private final ExpenseClaimPostingWriter approvedWriter = mock(ExpenseClaimPostingWriter.class);
  private final ExpenseClaimVoidWriter voidWriter = mock(ExpenseClaimVoidWriter.class);
  private final ExpenseSettlementWriter settlementWriter = mock(ExpenseSettlementWriter.class);
  private final ExpenseClaimPostingService service =
      new ExpenseClaimPostingService(approvedWriter, voidWriter, settlementWriter);

  private static ExpenseClaimApprovedEvent approvedEvent(UUID eventId) {
    return new ExpenseClaimApprovedEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(100_000L, "IDR"),
        "supplies",
        LocalDate.of(2026, 8, 1),
        Instant.parse("2026-08-02T09:00:00Z"));
  }

  private static ExpenseClaimVoidedEvent voidedEvent(UUID eventId) {
    return new ExpenseClaimVoidedEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(100_000L, "IDR"),
        "supplies",
        Instant.parse("2026-08-01T10:00:00Z"),
        Instant.parse("2026-08-02T09:00:00Z"));
  }

  private static ExpenseReimbursementSettledEvent settledEvent(UUID eventId) {
    return new ExpenseReimbursementSettledEvent(
        eventId,
        CLAIM_ID,
        TENANT,
        ORG_UNIT_ID,
        EMPLOYEE_ID,
        Money.ofMinor(100_000L, "IDR"),
        "PAYROLL",
        UUID.randomUUID(),
        2,
        Instant.parse("2026-08-03T09:00:00Z"));
  }

  @Test
  void handleApprovedBindsTheEventTenantAndDelegatesToTheApprovedWriter() {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = approvedEvent(eventId);
    when(approvedWriter.postApproved(event)).thenReturn(true);

    boolean posted = service.handleApproved(event);

    assertThat(posted).isTrue();
    verify(approvedWriter).postApproved(event);
  }

  @Test
  void handleVoidedBindsTheEventTenantAndDelegatesToTheVoidWriter() {
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = voidedEvent(eventId);
    when(voidWriter.postVoided(event)).thenReturn(true);

    boolean ran = service.handleVoided(event);

    assertThat(ran).isTrue();
    verify(voidWriter).postVoided(event);
  }

  @Test
  void handleSettledDelegatesToTheSettlementWriterOnTheHappyPath() {
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = settledEvent(eventId);
    when(settlementWriter.settle(event)).thenReturn(true);

    boolean ran = service.handleSettled(event);

    assertThat(ran).isTrue();
    verify(settlementWriter).settle(event);
  }

  @Test
  void handleSettledRecoversASettleOnceRaceAcrossDistinctEventIdsAsANoOp() {
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = settledEvent(eventId);
    when(settlementWriter.settle(event))
        .thenThrow(new DataIntegrityViolationException("uq_employee_expense_claim_ledger_claim"));
    when(settlementWriter.isSettledForReplay(CLAIM_ID)).thenReturn(true);

    boolean ran = service.handleSettled(event);

    // Recovered as a no-op — never a double post, never a propagated exception.
    assertThat(ran).isTrue();
    verify(settlementWriter).isSettledForReplay(CLAIM_ID);
  }

  @Test
  void handleSettledRethrowsWhenTheRaceHasNoRecoverableGuardRow() {
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event = settledEvent(eventId);
    DataIntegrityViolationException conflict =
        new DataIntegrityViolationException("unrelated constraint");
    when(settlementWriter.settle(event)).thenThrow(conflict);
    when(settlementWriter.isSettledForReplay(CLAIM_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.handleSettled(event))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isSameAs(conflict);
  }
}
