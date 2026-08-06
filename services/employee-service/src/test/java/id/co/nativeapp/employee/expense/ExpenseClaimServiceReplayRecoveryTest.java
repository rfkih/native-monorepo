package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Deterministic proof that a losing concurrent transition which aborts with a {@link
 * ClaimStateException} (the loser loaded the aggregate AFTER the winner flipped its state) recovers
 * to the winner's committed result — instead of leaking a spurious 409 to one of two identical,
 * intended-idempotent callers. The {@code ExpenseClaimApproveConcurrencyTest} exercises this only
 * when the CI scheduler happens to produce that interleaving; here it is forced with a stubbed
 * writer so both recovery branches (replay-exists → dedup; no-replay → rethrow) are pinned.
 *
 * <p>A plain unit test — the service is non-transactional orchestration over the writer, so a
 * mocked writer is sufficient and needs no database.
 */
class ExpenseClaimServiceReplayRecoveryTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "manager-actor";
  private static final UUID CLAIM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String KEY = "race-approve-key";

  @Test
  void aLosingApproveThatHitsTheStateGuardRecoversToTheApprovedClaimWhenItsKeyAlreadyWon()
      throws Exception {
    ExpenseClaimWriter writer = mock(ExpenseClaimWriter.class);
    ExpenseClaimService service = new ExpenseClaimService(writer);

    ExpenseClaim approved = mock(ExpenseClaim.class);
    when(approved.getId()).thenReturn(CLAIM_ID);
    when(approved.getStatus()).thenReturn(ClaimStatus.APPROVED);

    // The loser: the winner already flipped SUBMITTED→APPROVED, so the domain guard rejects it.
    when(writer.approve(eq(CLAIM_ID), any(), eq(KEY)))
        .thenThrow(new ClaimStateException(ClaimStatus.APPROVED, "approve"));
    // ...but its OWN (claim, key, approve) audit row exists — the winner committed it atomically
    // with the state flip — so this IS a replay, not a genuine wrong-state error.
    when(writer.isReplayedEvent(CLAIM_ID, KEY, ExpenseClaimWriter.ACTION_APPROVE)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM_ID)).thenReturn(Optional.of(approved));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> service.approve(CLAIM_ID, "ok", KEY));

    assertThat(result).isSameAs(approved);
    assertThat(result.getStatus()).isEqualTo(ClaimStatus.APPROVED);
  }

  @Test
  void aGenuineWrongStateApproveWithAFreshKeyStillThrowsTheStateExceptionAs409() {
    ExpenseClaimWriter writer = mock(ExpenseClaimWriter.class);
    ExpenseClaimService service = new ExpenseClaimService(writer);

    // A wrong-state transition with a key that never won anything (e.g. approving a CANCELLED
    // claim). No matching audit row → NOT a replay → the 409 must surface, never a silent 200.
    when(writer.approve(eq(CLAIM_ID), any(), eq("fresh-key")))
        .thenThrow(new ClaimStateException(ClaimStatus.CANCELLED, "approve"));
    when(writer.isReplayedEvent(CLAIM_ID, "fresh-key", ExpenseClaimWriter.ACTION_APPROVE))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> service.approve(CLAIM_ID, "ok", "fresh-key")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void theSameStateGuardRecoveryHoldsForANonApproveTransition() throws Exception {
    // The recovery path is shared across all six transitions — pin it on a SECOND action (cancel)
    // so a future per-method regression can't slip the ClaimStateException recovery on one of them.
    ExpenseClaimWriter writer = mock(ExpenseClaimWriter.class);
    ExpenseClaimService service = new ExpenseClaimService(writer);

    ExpenseClaim cancelled = mock(ExpenseClaim.class);
    when(cancelled.getId()).thenReturn(CLAIM_ID);
    when(cancelled.getStatus()).thenReturn(ClaimStatus.CANCELLED);

    when(writer.cancel(eq(CLAIM_ID), eq(KEY)))
        .thenThrow(new ClaimStateException(ClaimStatus.CANCELLED, "cancel"));
    when(writer.isReplayedEvent(CLAIM_ID, KEY, ExpenseClaimWriter.ACTION_CANCEL)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM_ID)).thenReturn(Optional.of(cancelled));

    ExpenseClaim result = TenantContext.callAs(TENANT, ACTOR, () -> service.cancel(CLAIM_ID, KEY));

    assertThat(result).isSameAs(cancelled);
    assertThat(result.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
  }
}
