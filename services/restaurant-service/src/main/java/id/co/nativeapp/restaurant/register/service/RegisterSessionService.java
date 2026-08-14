package id.co.nativeapp.restaurant.register.service;

import id.co.nativeapp.restaurant.register.domain.RegisterSessionAlreadyOpenException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Facade over {@link RegisterSessionWriter} (ADR 0036). Not itself {@code @Transactional} — the
 * transactional units live on the writer bean (proxy semantics, the SaleService/SaleWriter
 * pattern); this layer only adds the double-open race recovery: two concurrent opens both pass the
 * writer's best-effort probes, the {@code uq_crs_one_open_per_outlet} partial unique decides the
 * winner, and the loser is recovered here — same key → 200 with the winner's row (a client retry
 * storm), different key → 409 already-open (genuinely a second drawer).
 */
@Service
public class RegisterSessionService {

  private final RegisterSessionWriter writer;

  public RegisterSessionService(RegisterSessionWriter writer) {
    this.writer = writer;
  }

  /** Opens the outlet's drawer session (see class doc for the race recovery). */
  public OpenSessionResult open(OpenSessionRequest request, String idempotencyKey) {
    TenantContext.require();
    try {
      return writer.open(request, idempotencyKey);
    } catch (DataIntegrityViolationException conflict) {
      // Either the open-key unique (same-key race → replay) or the one-open-per-outlet partial
      // unique (second drawer → 409) fired. The re-read runs in a FRESH transaction — the aborted
      // insert transaction is poisoned.
      return writer
          .findByOpenKey(idempotencyKey)
          .map(existing -> new OpenSessionResult(existing, false))
          .orElseThrow(() -> new RegisterSessionAlreadyOpenException(request.businessId()));
    }
  }

  /** Closes a session (writer owns the lock + one-shot transition + outbox emit). */
  public RegisterSessionResponse close(
      UUID sessionId, CloseSessionRequest request, String idempotencyKey) {
    TenantContext.require();
    return writer.close(sessionId, request, idempotencyKey);
  }

  public Optional<RegisterSessionResponse> current(UUID businessId) {
    TenantContext.require();
    return writer.findCurrent(businessId);
  }

  /** The live per-tender expected breakdown for an OPEN session (ADR 0038, phase 1). */
  public RegisterExpectedResponse expectedBreakdown(UUID sessionId) {
    TenantContext.require();
    return writer.expectedBreakdown(sessionId);
  }

  /** The POS daily transaction summary (Z-report) for a session — OPEN (X-report) or CLOSED. */
  public RegisterSummaryResponse summarize(UUID sessionId) {
    TenantContext.require();
    return writer.summarize(sessionId);
  }

  public List<RegisterSessionResponse> history(UUID businessId) {
    TenantContext.require();
    return writer.findHistory(businessId);
  }
}
