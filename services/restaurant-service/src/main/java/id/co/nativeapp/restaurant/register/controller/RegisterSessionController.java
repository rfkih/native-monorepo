package id.co.nativeapp.restaurant.register.controller;

import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionResult;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
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
      @RequestBody OpenSessionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    OpenSessionResult result = service.open(request, idempotencyKey);
    return result.created()
        ? ResponseEntity.created(
                URI.create("/api/v1/register-sessions/" + result.session().id()))
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
      @RequestBody CloseSessionRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    return ResponseEntity.ok(service.close(id, request, idempotencyKey));
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

  /** The outlet's session history, most recent first (capped at 50). */
  @Operation(summary = "Register session history", description = "Most recent 50 sessions.")
  @GetMapping
  public ResponseEntity<List<RegisterSessionResponse>> history(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.history(businessId));
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
