package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ExpenseClaimWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Deterministic proof of {@link ExpenseClaimService}'s conflict-recovery branch — the one a
 * concurrent idempotency-key insert race triggers — without depending on thread timing. Mirrors
 * {@code SaleServiceConflictRecoveryTest}.
 *
 * <p><strong>Action-guarded recovery (S1/S2, code review).</strong> Every recovery now goes through
 * {@link ExpenseClaimWriter#isReplayedEvent}: a raced {@link DataIntegrityViolationException} is
 * treated as a genuine replay ONLY when an audit row for the EXACT (claim, key, action) triple
 * exists. When it does not — e.g. the same key reused for a DIFFERENT action on the same claim —
 * the service rethrows rather than masking the conflict as a successful re-read.
 */
class ExpenseClaimServiceConflictRecoveryTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "manager-sub";
  private static final UUID CLAIM = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

  private final ExpenseClaimWriter writer = mock(ExpenseClaimWriter.class);
  private final ExpenseClaimService service = new ExpenseClaimService(writer);

  private static ExpenseClaim existingClaim() {
    return new ExpenseClaim(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Money.ofMinor(250_000L, "IDR"),
        LocalDate.of(2026, 7, 15),
        null,
        null,
        null);
  }

  @Test
  void aUniqueConstraintConflictRecoversTheExistingClaimOnApprove() throws Exception {
    ExpenseClaim existing = existingClaim();
    when(writer.approve(CLAIM, "ok", "k-1"))
        .thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.isReplayedEvent(CLAIM, "k-1", ExpenseClaimWriter.ACTION_APPROVE)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.of(existing));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> service.approve(CLAIM, "ok", "k-1"));

    assertThat(result).isSameAs(existing);
  }

  @Test
  void aConflictWithNoRecoverableRowRethrowsOnApprove() throws Exception {
    when(writer.approve(CLAIM, "ok", "k-1"))
        .thenThrow(new DataIntegrityViolationException("dup key"));
    // A genuine replay of THIS action (the audit row exists) but the claim itself is somehow
    // unreadable — the original conflict, not a fabricated NoSuchElementException, must surface.
    when(writer.isReplayedEvent(CLAIM, "k-1", ExpenseClaimWriter.ACTION_APPROVE)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> service.approve(CLAIM, "ok", "k-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aUniqueConstraintConflictRecoversTheExistingClaimOnSubmit() throws Exception {
    ExpenseClaim existing = existingClaim();
    when(writer.submit(any(), any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.isReplayedEvent(CLAIM, "k-2", ExpenseClaimWriter.ACTION_SUBMIT)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.of(existing));

    ExpenseClaim result = TenantContext.callAs(TENANT, ACTOR, () -> service.submit(CLAIM, "k-2"));

    assertThat(result).isSameAs(existing);
  }

  @Test
  void aConflictFromAKeyReusedForADifferentActionRethrowsRatherThanMaskingAsReplay()
      throws Exception {
    // S1/S2: the SAME idempotency key was already used for CANCEL on this claim; a later SUBMIT
    // reusing it trips the (company_id, claim_id, idempotency_key) unique index too, but it is NOT
    // a replay of the submit — isReplayedEvent(..., ACTION_SUBMIT) correctly returns false because
    // the existing audit row's action is CANCEL, not SUBMIT.
    when(writer.submit(CLAIM, "shared-key"))
        .thenThrow(new DataIntegrityViolationException("dup key, different action"));
    when(writer.isReplayedEvent(CLAIM, "shared-key", ExpenseClaimWriter.ACTION_SUBMIT))
        .thenReturn(false);

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> service.submit(CLAIM, "shared-key")))
        .isInstanceOf(DataIntegrityViolationException.class);

    // Never masks the conflict with a stale re-read when it is not a genuine replay.
    verify(writer, never()).findByIdForReplay(any());
  }

  @Test
  void aConflictFromAKeyReusedForADifferentActionRethrowsOnCancel() throws Exception {
    when(writer.cancel(CLAIM, "shared-key"))
        .thenThrow(new DataIntegrityViolationException("dup key, different action"));
    when(writer.isReplayedEvent(CLAIM, "shared-key", ExpenseClaimWriter.ACTION_CANCEL))
        .thenReturn(false);

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> service.cancel(CLAIM, "shared-key")))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(writer, never()).findByIdForReplay(any());
  }

  @Test
  void aGenuineReplayOnRefuseStillRecoversViaTheExistingClaim() throws Exception {
    ExpenseClaim existing = existingClaim();
    when(writer.refuse(eq(CLAIM), any(), eq("k-3")))
        .thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.isReplayedEvent(CLAIM, "k-3", ExpenseClaimWriter.ACTION_REFUSE)).thenReturn(true);
    when(writer.findByIdForReplay(CLAIM)).thenReturn(Optional.of(existing));

    ExpenseClaim result =
        TenantContext.callAs(TENANT, ACTOR, () -> service.refuse(CLAIM, "no receipt", "k-3"));

    assertThat(result).isSameAs(existing);
  }
}
