package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@link ExpenseClaimWriter}'s transactional units of work and owns the
 * concurrency-safe idempotency contract for the six guarded transitions (submit/cancel/approve/
 * refuse/payDirect/voidClaim): when two concurrent callers race the SAME {@code (claim,
 * idempotency-key)} transition, the loser's {@link ExpenseClaimWriter} transaction aborts and this
 * (non-transactional) service catches it and recovers with a separate-transaction re-read ({@link
 * ExpenseClaimWriter#findByIdForReplay}) — mirroring {@code SaleService}/{@code AssignmentWriter}'s
 * conflict-recovery idiom. No second mutation, no second outbox write is ever attempted on the
 * recovery path.
 *
 * <p>The loser can abort in either of two ways depending on the interleaving — a {@link
 * DataIntegrityViolationException} on the {@code UNIQUE (company_id, claim_id, idempotency_key)}
 * audit insert, or a {@link ClaimStateException} from the domain state guard when it loaded the
 * aggregate after the winner had already flipped its state. Both are recovered identically; see
 * {@link #recoverReplay}.
 *
 * <p><strong>The recovery is action-guarded (S1/S2, code review).</strong> The DB {@code UNIQUE
 * (company_id, claim_id, idempotency_key)} does not include {@code action}, so a client that reuses
 * the SAME idempotency key for a DIFFERENT action on the SAME claim ALSO trips it — that is NOT a
 * replay of anything, and blindly re-reading the claim on any {@link
 * DataIntegrityViolationException} would silently mask a genuine integrity error as a false
 * "success". Before treating the conflict as a replay, {@link #recoverReplay} confirms via {@link
 * ExpenseClaimWriter#isReplayedEvent} that an audit row for THIS EXACT (claim, key, action) triple
 * exists; if it does not, the original exception is rethrown (surfacing as a conflict, never a
 * silently-wrong 200).
 */
@Service
public class ExpenseClaimService {

  private final ExpenseClaimWriter writer;

  public ExpenseClaimService(ExpenseClaimWriter writer) {
    this.writer = writer;
  }

  /** Creates a DRAFT claim for the caller. Not a guarded transition — no idempotency key. */
  public ExpenseClaim create(CreateClaimCommand command) {
    TenantContext.require();
    return writer.create(command);
  }

  /** Replaces every editable field of the caller's own DRAFT claim. */
  public ExpenseClaim updateDraft(UUID claimId, CreateClaimCommand command) {
    TenantContext.require();
    return writer.updateDraft(claimId, command);
  }

  /** Submits the caller's own DRAFT claim, idempotently. */
  public ExpenseClaim submit(UUID claimId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.submit(claimId, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_SUBMIT, conflict);
    }
  }

  /** Cancels the caller's own DRAFT/SUBMITTED claim, idempotently. */
  public ExpenseClaim cancel(UUID claimId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.cancel(claimId, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_CANCEL, conflict);
    }
  }

  /** Approves a SUBMITTED claim, idempotently. Self-approval is rejected (owners included). */
  public ExpenseClaim approve(UUID claimId, String comment, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.approve(claimId, comment, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_APPROVE, conflict);
    }
  }

  /**
   * Refuses a SUBMITTED claim, idempotently. The comment is required (enforced by the aggregate).
   */
  public ExpenseClaim refuse(UUID claimId, String comment, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.refuse(claimId, comment, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_REFUSE, conflict);
    }
  }

  /**
   * Pays an APPROVED, un-linked claim directly, idempotently (ADR 0030 §6 DIRECT path, Phase E4).
   */
  public ExpenseClaim payDirect(UUID claimId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.payDirect(claimId, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_PAY_DIRECT, conflict);
    }
  }

  /**
   * Voids an APPROVED, un-linked, un-settled claim, idempotently (ADR 0030 §5, Phase E7). The
   * comment is required (enforced by the aggregate).
   */
  public ExpenseClaim voidClaim(UUID claimId, String comment, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.voidClaim(claimId, comment, idempotencyKey);
    } catch (DataIntegrityViolationException | ClaimStateException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_VOID, conflict);
    }
  }

  /**
   * Recovers a losing-racer exception ONLY when it is a genuine replay of THIS (claim, key, action)
   * triple; otherwise rethrows {@code conflict} unchanged (S1/S2, code review).
   *
   * <p>Two exceptions can surface on the losing racer, depending on the interleaving of the two
   * concurrent same-key transitions (both are handled identically — dedup iff a matching audit row
   * now exists):
   *
   * <ul>
   *   <li>{@link DataIntegrityViolationException} — the loser reached the {@code UNIQUE
   *       (company_id, claim_id, idempotency_key)} audit insert while the winner's row was already
   *       committed (the loser saw the aggregate still in the pre-transition state when it loaded
   *       it); and
   *   <li>{@link ClaimStateException} — the loser loaded the aggregate AFTER the winner committed
   *       the transition, so the domain state guard rejected it BEFORE it reached the audit insert.
   *       This is the timing the concurrency test exposes on a loaded CI runner. Left uncaught it
   *       would leak a spurious 409 to one of two identical, intended-idempotent callers.
   * </ul>
   *
   * The {@link ExpenseClaimWriter#isReplayedEvent} guard is what keeps this safe for the {@code
   * ClaimStateException} case too: a genuine wrong-state transition (a FRESH key against an
   * already-transitioned claim — e.g. approving a CANCELLED claim) finds NO matching audit row and
   * is rethrown as the 409 it should be; only a same-(claim, key, action) row — which the winner
   * commits atomically with the state flip, so it is visible once the loser sees the flipped state
   * — is treated as a replay.
   */
  private ExpenseClaim recoverReplay(
      UUID claimId, String idempotencyKey, String action, RuntimeException conflict) {
    if (!writer.isReplayedEvent(claimId, idempotencyKey, action)) {
      throw conflict;
    }
    return writer.findByIdForReplay(claimId).orElseThrow(() -> conflict);
  }
}
