package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@link LeaveRequestWriter}'s transactional units of work and owns the
 * concurrency-safe idempotency contract, mirroring {@code ExpenseClaimService}: when a concurrent
 * caller races the same unique-constraint insert, the loser's writer transaction aborts with a
 * {@link DataIntegrityViolationException} (a PostgreSQL transaction is poisoned once a constraint
 * fires), so this (non-transactional) service catches it and recovers with a separate-transaction
 * re-read. No second mutation, no second audit row is ever attempted on the recovery path.
 */
@Service
public class LeaveRequestService {

  private final LeaveRequestWriter writer;

  public LeaveRequestService(LeaveRequestWriter writer) {
    this.writer = writer;
  }

  /**
   * Creates (submits) a leave request for the caller, idempotently. {@link #create} recovery is
   * UNSCOPED by action — see {@link LeaveRequestWriter#findByIdempotencyKeyForReplay}'s Javadoc for
   * why that is safe here.
   */
  public LeaveRequest create(
      LeaveType leaveType,
      LocalDate startDate,
      LocalDate endDate,
      int days,
      String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.create(leaveType, startDate, endDate, days, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return writer.findByIdempotencyKeyForReplay(idempotencyKey).orElseThrow(() -> conflict);
    }
  }

  /** Cancels the caller's own SUBMITTED request, idempotently. */
  public LeaveRequest cancel(UUID requestId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.cancel(requestId, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(requestId, idempotencyKey, LeaveRequestWriter.ACTION_CANCEL, conflict);
    }
  }

  /** Approves a SUBMITTED request, idempotently. */
  public LeaveRequest approve(UUID requestId, String note, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.approve(requestId, note, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(requestId, idempotencyKey, LeaveRequestWriter.ACTION_APPROVE, conflict);
    }
  }

  /**
   * Rejects a SUBMITTED request, idempotently. The note is required (enforced by the aggregate).
   */
  public LeaveRequest reject(UUID requestId, String note, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.reject(requestId, note, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      return recoverReplay(requestId, idempotencyKey, LeaveRequestWriter.ACTION_REJECT, conflict);
    }
  }

  /**
   * Recovers a {@link DataIntegrityViolationException} ONLY when it is a genuine replay of THIS
   * (request, key, action) triple; otherwise rethrows {@code conflict} unchanged (the expense-claim
   * S1/S2 idiom).
   */
  private LeaveRequest recoverReplay(
      UUID requestId,
      String idempotencyKey,
      String action,
      DataIntegrityViolationException conflict) {
    if (!writer.isReplayedEvent(requestId, idempotencyKey, action)) {
      throw conflict;
    }
    return writer.findByIdForReplay(requestId).orElseThrow(() -> conflict);
  }
}
