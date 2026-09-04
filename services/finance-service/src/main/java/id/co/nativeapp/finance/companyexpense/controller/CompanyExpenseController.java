package id.co.nativeapp.finance.companyexpense.controller;

import id.co.nativeapp.finance.companyexpense.dto.CompanyExpenseResponse;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseReader;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
 * REST edge for company expenses (ADR 0072). Routed by the gateway under FINANCE_ROLES
 * (owner/accountant), mirroring AP. {@code POST} accepts an optional {@code Idempotency-Key} header
 * (≤64 chars) — a retried submit with the same key replays instead of double-recording.
 */
@RestController
@RequestMapping("/api/v1/company-expenses")
@Tag(
    name = "Company Expenses",
    description =
        "First-party company expenses (ADR 0072): money posts at submit; an INVENTORY submit also"
            + " instructs the stock receive.")
public class CompanyExpenseController {

  private final CompanyExpenseService service;
  private final CompanyExpenseReader reader;

  public CompanyExpenseController(CompanyExpenseService service, CompanyExpenseReader reader) {
    this.service = service;
    this.reader = reader;
  }

  /** Records a POSTED company expense; an INVENTORY submit also instructs the stock receive. */
  @Operation(
      summary = "Record a company expense",
      description =
          "Posts the money to the GL at submit (GENERAL by gl_hint; INVENTORY to HPP or GRNI per"
              + " the inventory method) and, for INVENTORY, instructs the stock receive via"
              + " InventoryPurchaseRecorded (ADR 0072). Idempotency-Key replays the same submit.")
  @PostMapping
  public ResponseEntity<Map<String, UUID>> record(
      @RequestBody RecordCompanyExpenseRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    CompanyExpenseService.RecordResult result =
        service.recordWithOutcome(request, normalizeKey(idempotencyKey));
    Map<String, UUID> body = Map.of("id", result.id());
    if (result.replayed()) {
      // An idempotent retry is 200, not a second 201 (ENGINEERING-STANDARDS §1.1).
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/company-expenses/" + result.id())).body(body);
  }

  /** Recent expenses, newest first. */
  @Operation(summary = "List recent company expenses", description = "Newest first; no lines.")
  @GetMapping
  public List<CompanyExpenseResponse> list(@RequestParam(required = false) Integer limit) {
    return reader.listRecent(limit);
  }

  /** One expense with its ingredient lines. */
  @Operation(
      summary = "Get one company expense",
      description = "Summary plus the ingredient lines (empty for GENERAL).")
  @GetMapping("/{id}")
  public CompanyExpenseResponse get(@PathVariable UUID id) {
    return reader.getById(id);
  }

  /** Voids a POSTED expense — money-side contra only; stock is adjusted via opname/Atur jumlah. */
  @Operation(
      summary = "Void a company expense",
      description =
          "Posts the exact contra of the stored journal (money-side only — stock is corrected"
              + " operationally, ADR 0072); 409 when already void.")
  @PostMapping("/{id}/void")
  public Map<String, UUID> voidExpense(@PathVariable UUID id) {
    return Map.of("id", service.voidExpense(id));
  }

  private static String normalizeKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return null;
    }
    String key = idempotencyKey.strip();
    if (key.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
    }
    return key;
  }
}
