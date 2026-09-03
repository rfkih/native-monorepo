package id.co.nativeapp.finance.companyexpense.controller;

import id.co.nativeapp.finance.companyexpense.dto.CompanyExpenseResponse;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseReader;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST edge for company expenses (ADR 0072). Routed by the gateway under FINANCE_ROLES
 * (owner/accountant), mirroring AP. {@code POST} accepts an optional {@code Idempotency-Key} header
 * (≤64 chars) — a retried submit with the same key replays instead of double-recording.
 */
@RestController
@RequestMapping("/api/v1/company-expenses")
public class CompanyExpenseController {

  private final CompanyExpenseService service;
  private final CompanyExpenseReader reader;

  public CompanyExpenseController(CompanyExpenseService service, CompanyExpenseReader reader) {
    this.service = service;
    this.reader = reader;
  }

  /** Records a POSTED company expense; an INVENTORY submit also instructs the stock receive. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, UUID> record(
      @RequestBody RecordCompanyExpenseRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    return Map.of("id", service.record(request, normalizeKey(idempotencyKey)));
  }

  /** Recent expenses, newest first. */
  @GetMapping
  public List<CompanyExpenseResponse> list(@RequestParam(required = false) Integer limit) {
    return reader.listRecent(limit);
  }

  /** One expense with its ingredient lines. */
  @GetMapping("/{id}")
  public CompanyExpenseResponse get(@PathVariable UUID id) {
    return reader.getById(id);
  }

  /** Voids a POSTED expense — money-side contra only; stock is adjusted via opname/Atur jumlah. */
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
