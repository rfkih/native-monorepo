package id.co.nativeapp.restaurant.integrity.controller;

import id.co.nativeapp.restaurant.integrity.dto.SalesIntegrityReportResponse;
import id.co.nativeapp.restaurant.integrity.service.SalesIntegrityReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sales-leak report (ADR 0074) — {@code GET /api/v1/sales-integrity/report}.
 *
 * <p><strong>Owner-only.</strong> The gateway restricts this whole path prefix to {@code
 * OWNER_ROLES}, narrower than the {@code POS_ROLES} surface the rest of restaurant-service exposes,
 * because some findings name an individual and a manager can be the subject of one. The path
 * segment is {@code sales-integrity}, distinct from {@code sales}, so it cannot fall through to the
 * general sales route.
 *
 * <p>Calls {@link SalesIntegrityReader} directly rather than through a service facade: the reader
 * IS the service layer (it carries the {@code @Transactional} + RLS-GUC advice), and there is one
 * operation with nothing to orchestrate, so a pass-through would add a hop without adding a seam.
 *
 * <p>Returns no user-facing prose — every signal is a machine enum the console renders copy for
 * (rule 9), and every amount is integer minor units with its ISO-4217 code (rule 8).
 */
@Tag(
    name = "Sales integrity",
    description = "Detect revenue that may never have been rung up (owner-only)")
@RestController
@RequestMapping("/api/v1/sales-integrity")
@Validated
public class SalesIntegrityController {

  private final SalesIntegrityReader reader;

  public SalesIntegrityController(SalesIntegrityReader reader) {
    this.reader = reader;
  }

  /**
   * Builds the leak report for one outlet over {@code [from, to)}. All three params are required —
   * the client owns the period choice, and defaulting a window server-side would silently change
   * every figure when the default moved.
   */
  @Operation(
      summary = "Sales-leak report for an outlet and window",
      description =
          "Estimates revenue that may never have been recorded, from evidence restaurant-service"
              + " already holds: tracked items and ingredients counted short, hours the till went"
              + " dark against the outlet's own baseline, sales rung outside any register session,"
              + " trading days that never closed, and register-close variance patterns. The"
              + " headline is a RANGE — the low bound counts only tightly quantified findings, the"
              + " high bound adds the ingredient estimate, which has innocent explanations Native"
              + " cannot yet record. Signals that did not fire are omitted, not returned at zero."
              + " The `coverage` block states what the report could NOT see; read it before"
              + " trusting a small total. Nothing here is posted to the ledger.")
  @GetMapping("/report")
  public ResponseEntity<SalesIntegrityReportResponse> report(
      @RequestParam UUID businessId, @RequestParam Instant from, @RequestParam Instant to) {
    if (!to.isAfter(from)) {
      throw new IllegalArgumentException("'to' must be after 'from'");
    }
    return ResponseEntity.ok(reader.report(businessId, from, to));
  }
}
