package id.co.nativeapp.restaurant.register.controller;

import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.ClosedSessionSummaryResponse;
import id.co.nativeapp.restaurant.register.dto.CorrectCloseRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register sessions — closing kasir (ADR 0036). Gateway-routed under POS_ROLES (the cashier opens
 * and closes the till). Both mutations REQUIRE an {@code Idempotency-Key} header (the
 * payroll-settlement idiom: keyless → 400, same-key replay → 200, the DB uniques backstop races).
 */
@RestController
@RequestMapping("/api/v1/register-sessions")
public class RegisterSessionController {

  private final RegisterSessionService service;

  public RegisterSessionController(RegisterSessionService service) {
    this.service = service;
  }

  /**
   * Opens the outlet's drawer session (one OPEN per outlet). 201 + Location on a fresh open, 200 on
   * a same-key replay, 409 when another session is already OPEN at the outlet.
   */
  @Operation(
      summary = "Open a register session",
      description =
          "Opens the outlet's cash-drawer session with an optional counted opening float. One OPEN"
              + " session per outlet; Idempotency-Key required (replay returns 200). 409 when a"
              + " session is already open.")
  @PostMapping
  public ResponseEntity<RegisterSessionResponse> open(
      @jakarta.validation.Valid @RequestBody OpenSessionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    OpenSessionResult result = service.open(request, idempotencyKey);
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/register-sessions/" + result.session().id()))
            .body(result.session())
        : ResponseEntity.ok(result.session());
  }

  /**
   * Closes a session with the cashier's physical drawer count. The server computes expected cash
   * and the signed over/short, emits {@code RegisterSessionClosed} in the same transaction, and
   * returns the closed session. Same-key replay → 200; double-close with a different key → 409.
   */
  @Operation(
      summary = "Close a register session",
      description =
          "Closes the drawer with the counted cash; expected cash and the signed over/short are"
              + " server-computed and the RegisterSessionClosed event is emitted atomically."
              + " Idempotency-Key required (replay returns 200); a non-OPEN session with a new key"
              + " returns 409.")
  @PostMapping("/{id}/close")
  public ResponseEntity<RegisterSessionResponse> close(
      @PathVariable("id") UUID id,
      @jakarta.validation.Valid @RequestBody CloseSessionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    return ResponseEntity.ok(service.close(id, request, idempotencyKey));
  }

  /**
   * Manager/owner CASH-count CORRECTION of an already-CLOSED session (ADR 0064) — the fix for a
   * cashier who closed with the wrong counted cash. Finance reverses the prior cash-variance
   * journal and posts the corrected one (append-only). Gateway-gated to owner/manager. 200 with the
   * amended session; 409 if the session is not CLOSED; 422 if it is too old / predates the feature.
   */
  @Operation(
      summary = "Correct a closed register session's cash count",
      description =
          "Manager/owner-only. Re-derives the over/short from the corrected counted cash and reverses"
              + " + re-posts the finance variance (books stay balanced). Recent, unsealed closes"
              + " only; correcting to the recorded value is a no-op. A required reason is recorded"
              + " for audit.")
  @PostMapping("/{id}/correct-close")
  public ResponseEntity<RegisterSessionResponse> correctClose(
      @PathVariable("id") UUID id,
      @jakarta.validation.Valid @RequestBody CorrectCloseRequest request) {
    return ResponseEntity.ok(service.correctClose(id, request));
  }

  /** The outlet's current OPEN session — 200 with the session, or 204 when none is open. */
  @Operation(
      summary = "Current register session",
      description = "The outlet's OPEN session, or 204 No Content when the drawer is closed.")
  @GetMapping("/current")
  public ResponseEntity<RegisterSessionResponse> current(@RequestParam UUID businessId) {
    return service
        .current(businessId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * The OPEN session's live per-tender EXPECTED breakdown (cash/card/QRIS/online), for the close
   * screen (ADR 0038). 404 for an unknown session, 409 when it is not OPEN.
   */
  @Operation(
      summary = "Register session expected breakdown",
      description =
          "The live per-tender expected amounts (cash/card/QRIS/online) for the OPEN session, so the"
              + " close screen can show what to count/verify per tender. A preview; the close"
              + " snapshots the authoritative figures.")
  @GetMapping("/{id}/expected")
  public ResponseEntity<RegisterExpectedResponse> expected(@PathVariable("id") UUID id) {
    return ResponseEntity.ok(service.expectedBreakdown(id));
  }

  /**
   * The POS daily transaction summary (Z-report) for a session — the day's sales aggregates
   * (transaction count, gross/discount/service/tax breakdown, per-tender net) plus the cash
   * reconciliation, printed at close or any time during the day. Works for an OPEN session (a live
   * X-report over {@code [openedAt, now)}) and a CLOSED one (the final Z-report over {@code
   * [openedAt, closedAt)}). 404 for an unknown session. Reporting only — the tax line is
   * illustrative unless an SME has replaced the seeded rate.
   */
  @Operation(
      summary = "Register session daily summary (Z-report)",
      description =
          "The day's sales aggregates for the session (transaction count, gross/discount/service/tax"
              + " breakdown, per-tender net sales, refunds) plus the cash reconciliation. Works for"
              + " an OPEN session (live X-report) and a CLOSED one (final Z-report). Reporting only;"
              + " the tax line is illustrative unless the seeded rate has been replaced by an SME.")
  @GetMapping("/{id}/summary")
  public ResponseEntity<RegisterSummaryResponse> summary(@PathVariable("id") UUID id) {
    return ResponseEntity.ok(service.summarize(id));
  }

  /** The outlet's session history, most recent first (capped at 50). */
  @Operation(summary = "Register session history", description = "Most recent 50 sessions.")
  @GetMapping
  public ResponseEntity<List<RegisterSessionResponse>> history(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.history(businessId));
  }

  /**
   * The outlet's CLOSED sessions (newest first, capped) with each closed day's net sales and
   * transaction count — the manager/owner "past closed-day sales history" browse. Each row's net
   * equals that session's Z-report net; tap-through opens {@code /{id}/summary}. POS_ROLES at the
   * gateway (same as the summary); the owner/manager restriction is a client affordance. Reporting
   * only.
   */
  @Operation(
      summary = "Closed register session history (with sales)",
      description =
          "Recent CLOSED sessions for the outlet, newest first, each with its net sales and"
              + " transaction count — the manager/owner past-day history browse. Reporting only.")
  @GetMapping("/closed")
  public ResponseEntity<List<ClosedSessionSummaryResponse>> closedHistory(
      @RequestParam UUID businessId) {
    return ResponseEntity.ok(service.closedHistory(businessId));
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to open or close a register session");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
    }
  }
}
