package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStateException;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@link OvertimeEntryWriter}'s transactional units of work and owns the
 * concurrency-safe idempotency contract — the {@link LeaveRequestService} idiom, applied to
 * overtime entries.
 */
@Service
public class OvertimeEntryService {

  private final OvertimeEntryWriter writer;

  public OvertimeEntryService(OvertimeEntryWriter writer) {
    this.writer = writer;
  }

  /** Creates (submits) an overtime entry for the caller, idempotently. */
  public OvertimeEntry create(
      LocalDate workDate, int minutes, DayKind dayKind, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.create(workDate, minutes, dayKind, idempotencyKey);
    } catch (DataIntegrityViolationException | TimeoffStateException conflict) {
      return writer.findByIdempotencyKeyForReplay(idempotencyKey).orElseThrow(() -> conflict);
    }
  }

  /** Cancels the caller's own SUBMITTED entry, idempotently. */
  public OvertimeEntry cancel(UUID entryId, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.cancel(entryId, idempotencyKey);
    } catch (DataIntegrityViolationException | TimeoffStateException conflict) {
      return recoverReplay(entryId, idempotencyKey, OvertimeEntryWriter.ACTION_CANCEL, conflict);
    }
  }

  /** Approves a SUBMITTED entry, idempotently. */
  public OvertimeEntry approve(UUID entryId, String note, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.approve(entryId, note, idempotencyKey);
    } catch (DataIntegrityViolationException | TimeoffStateException conflict) {
      return recoverReplay(entryId, idempotencyKey, OvertimeEntryWriter.ACTION_APPROVE, conflict);
    }
  }

  /** Rejects a SUBMITTED entry, idempotently. */
  public OvertimeEntry reject(UUID entryId, String note, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.reject(entryId, note, idempotencyKey);
    } catch (DataIntegrityViolationException | TimeoffStateException conflict) {
      return recoverReplay(entryId, idempotencyKey, OvertimeEntryWriter.ACTION_REJECT, conflict);
    }
  }

  private OvertimeEntry recoverReplay(
      UUID entryId, String idempotencyKey, String action, RuntimeException conflict) {
    if (!writer.isReplayedEvent(entryId, idempotencyKey, action)) {
      throw conflict;
    }
    return writer.findByIdForReplay(entryId).orElseThrow(() -> conflict);
  }
}
