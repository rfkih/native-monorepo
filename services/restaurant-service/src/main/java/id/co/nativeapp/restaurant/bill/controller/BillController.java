package id.co.nativeapp.restaurant.bill.controller;

import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.BillSummaryResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the bill (guest tab) feature:
 *
 * <ul>
 *   <li>{@code POST /api/v1/bills} — open a new OPEN bill
 *   <li>{@code GET /api/v1/bills?businessId=...&status=OPEN[&tableId=...]} — list bills
 *   <li>{@code GET /api/v1/bills/{id}} — get a bill with lines and live breakdown
 *   <li>{@code POST /api/v1/bills/{id}/lines} — append a round of items to an OPEN bill
 *   <li>{@code DELETE /api/v1/bills/{id}/lines/{lineId}} — remove a line from an OPEN bill
 *   <li>{@code POST /api/v1/bills/{id}/pay} — finalise an OPEN bill (revenue recognised here)
 *   <li>{@code POST /api/v1/bills/{id}/cancel} — cancel an OPEN bill
 * </ul>
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Bills", description = "Guest bill (tab) lifecycle: open, append, pay, cancel")
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

  private final BillService billService;

  public BillController(BillService billService) {
    this.billService = billService;
  }

  /**
   * Opens a new OPEN bill for a guest. Multiple OPEN bills per table are allowed (one per guest).
   * Returns 201 Created + Location header.
   */
  @Operation(
      summary = "Open a new guest bill",
      description =
          "Opens a new OPEN bill for a guest. Multiple OPEN bills per table are allowed (one per"
              + " guest). Returns 201 Created.")
  @PostMapping
  public ResponseEntity<BillResponse> open(@Valid @RequestBody OpenBillRequest request) {
    BillResponse body = billService.open(request);
    return ResponseEntity.created(URI.create("/api/v1/bills/" + body.id())).body(body);
  }

  /**
   * Lists bills for a business, filtered by status (default OPEN) and optionally by table. Returns
   * a summary list (no line detail, no breakdown).
   */
  @Operation(
      summary = "List bills",
      description =
          "Lists bills for a business, filtered by status (default OPEN) and optionally by"
              + " tableId. Returns a summary list without line detail.")
  @GetMapping
  public ResponseEntity<List<BillSummaryResponse>> list(
      @RequestParam UUID businessId,
      @RequestParam(defaultValue = "OPEN") String status,
      @RequestParam(required = false) UUID tableId) {
    return ResponseEntity.ok(billService.list(businessId, status, tableId));
  }

  /**
   * Returns the full bill — header, lines (with modifiers), and a live price breakdown. Returns 200
   * OK.
   */
  @Operation(
      summary = "Get a bill",
      description =
          "Returns the full bill with lines, modifier snapshots, and a live price breakdown."
              + " Returns 200 OK or 404 if not found.")
  @GetMapping("/{id}")
  public ResponseEntity<BillResponse> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(billService.getById(id));
  }

  /**
   * Appends a round of items to an OPEN bill. Validates items, currency, modifiers. Returns 200 OK
   * with the updated bill (including the new lines and the live breakdown).
   */
  @Operation(
      summary = "Append items to an OPEN bill",
      description =
          "Appends a round of items to an OPEN bill. Validates items, currency homogeneity, and"
              + " modifiers. Returns 200 OK with the updated bill, or 409 if the bill is not OPEN.")
  @PostMapping("/{id}/lines")
  public ResponseEntity<BillResponse> appendLines(
      @PathVariable UUID id, @Valid @RequestBody AppendLinesRequest request) {
    return ResponseEntity.ok(billService.appendLines(id, request));
  }

  /** Removes a line from an OPEN bill (before pay). Returns 204 No Content. */
  @Operation(
      summary = "Remove a line from an OPEN bill",
      description =
          "Removes a single line from an OPEN bill before it is paid. Returns 204 No Content, or"
              + " 409 if the bill is not OPEN.")
  @DeleteMapping("/{id}/lines/{lineId}")
  public ResponseEntity<Void> removeLine(@PathVariable UUID id, @PathVariable UUID lineId) {
    billService.removeLine(id, lineId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Finalises an OPEN bill: computes the final price breakdown, deducts stock, records the sale
   * ({@code SaleRecorded} outbox). Idempotent — re-paying a PAID bill returns the current state.
   * Returns 200 OK.
   */
  @Operation(
      summary = "Pay (finalise) a bill",
      description =
          "Finalises an OPEN bill: deducts stock, records the sale (SaleRecorded outbox in the"
              + " same transaction). Idempotent on a PAID bill. Returns 200 OK, 409 if not OPEN"
              + " (and not PAID), or 422 on insufficient stock.")
  @PostMapping("/{id}/pay")
  public ResponseEntity<BillResponse> pay(
      @PathVariable UUID id, @Valid @RequestBody PayBillRequest request) {
    return ResponseEntity.ok(billService.payBill(id, request));
  }

  /** Cancels an OPEN bill (no sale, no stock change). Returns 204 No Content. */
  @Operation(
      summary = "Cancel a bill",
      description =
          "Cancels an OPEN bill. No sale is recorded and no stock is changed. Returns 204 No"
              + " Content, or 409 if the bill is not OPEN.")
  @PostMapping("/{id}/cancel")
  public ResponseEntity<Void> cancel(@PathVariable UUID id) {
    billService.cancelBill(id);
    return ResponseEntity.noContent().build();
  }
}
