package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@link ExpenseClaimWriter}'s transactional units of work and owns the
 * concurrency-safe idempotency contract for the four guarded transitions (submit/cancel/approve/
 * refuse): when two concurrent callers race the SAME {@code (claim, idempotency-key)} insert, the
 * loser's {@link ExpenseClaimWriter} transaction aborts with a {@link
 * DataIntegrityViolationException} (a PostgreSQL transaction is poisoned once a constraint fires),
 * so this (non-transactional) service catches it and recovers with a separate-transaction re-read
 * ({@link ExpenseClaimWriter#findByIdForReplay}) — mirroring {@code SaleService}/{@code
 * AssignmentWriter}'s conflict-recovery idiom. No second mutation, no second outbox write is ever
 * attempted on the recovery path.
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
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_SUBMIT, conflict);
    }
  }

  /** Cancels the caller's own DRAFT/SUBMITTED claim, idempotently. */
  public ExpenseClaim cancel(UUID claimId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.cancel(claimId, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_CANCEL, conflict);
    }
  }

  /** Approves a SUBMITTED claim, idempotently. Self-approval is rejected (owners included). */
  public ExpenseClaim approve(UUID claimId, String comment, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.approve(claimId, comment, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
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
    } catch (DataIntegrityViolationException conflict) {
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
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(claimId, idempotencyKey, ExpenseClaimWriter.ACTION_PAY_DIRECT, conflict);
    }
  }

  /**
   * Recovers a {@link DataIntegrityViolationException} ONLY when it is a genuine replay of THIS
   * (claim, key, action) triple; otherwise rethrows {@code conflict} unchanged (S1/S2, code
   * review).
   */
  private ExpenseClaim recoverReplay(
      UUID claimId,
      String idempotencyKey,
      String action,
      DataIntegrityViolationException conflict) {
    if (!writer.isReplayedEvent(claimId, idempotencyKey, action)) {
      throw conflict;
    }
    return writer.findByIdForReplay(claimId).orElseThrow(() -> conflict);
  }
}
