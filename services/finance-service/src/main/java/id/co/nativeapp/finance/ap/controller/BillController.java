package id.co.nativeapp.finance.ap.controller;

import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.dto.BillSummaryResponse;
import id.co.nativeapp.finance.ap.dto.CreateBillRequest;
import id.co.nativeapp.finance.ap.dto.PostBillRequest;
import id.co.nativeapp.finance.ap.dto.RecordPaymentRequest;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillPaymentWriter;
import id.co.nativeapp.finance.ap.service.BillReader;
import id.co.nativeapp.finance.ap.service.BillWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ap/bills} — the AP bill lifecycle for the bound tenant: create draft, list,
 * detail, post, void, and record a payment. Writes go through the RLS-bound writers; post/void/pay
 * post to the double-entry GL in the same transaction. Every response is a DTO (never the entity);
 * mutating endpoints return the fresh bill detail. Typed faults map to RFC-7807 via {@link
 * ApAdvice} (404 not-found, 409 invalid-state, 400 bad-input).
 */
@Tag(name = "Bills", description = "AP bills for the bound tenant (draft → post → paid/void).")
@RestController
@RequestMapping("/api/v1/ap/bills")
@Validated
public class BillController {

  private final BillWriter billWriter;
  private final BillPaymentWriter billPaymentWriter;
  private final BillReader billReader;

  public BillController(
      BillWriter billWriter, BillPaymentWriter billPaymentWriter, BillReader billReader) {
    this.billWriter = billWriter;
    this.billPaymentWriter = billPaymentWriter;
    this.billReader = billReader;
  }

  @Operation(summary = "Create a draft bill (server computes totals + illustrative tax)")
  @PostMapping
  public ResponseEntity<BillDetailResponse> create(@Valid @RequestBody CreateBillRequest request) {
    List<BillLineInput> lines =
        request.lines().stream()
            .map(l -> new BillLineInput(l.description(), l.quantity(), l.unitPriceMinor()))
            .toList();
    UUID id =
        billWriter.createDraft(request.vendorId(), request.currency(), request.taxable(), lines);
    BillDetailResponse detail = billReader.detail(id);
    return ResponseEntity.created(URI.create("/api/v1/ap/bills/" + detail.id())).body(detail);
  }

  @Operation(summary = "List bills, optionally filtered by status / vendor / bill-month")
  @GetMapping
  public List<BillSummaryResponse> list(
      @RequestParam(required = false)
          @Pattern(
              regexp = "DRAFT|POSTED|PARTIALLY_PAID|PAID|VOID",
              message = "status must be a valid bill status")
          String status,
      @RequestParam(required = false) UUID vendorId,
      @RequestParam(required = false)
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    return billReader.list(status, vendorId, period);
  }

  @Operation(summary = "Get a bill detail (header + lines + payments)")
  @GetMapping("/{id}")
  public BillDetailResponse get(@PathVariable UUID id) {
    return billReader.detail(id);
  }

  @Operation(summary = "Post a draft bill (posts Dr expense / Cr AP (+ VAT input) to the GL)")
  @PostMapping("/{id}/post")
  public BillDetailResponse post(
      @PathVariable UUID id, @Valid @RequestBody(required = false) PostBillRequest request) {
    Integer termDays = request == null ? null : request.termDays();
    return billReader.detail(billWriter.post(id, termDays));
  }

  @Operation(summary = "Void a bill (posts the contra GL entry for a posted bill)")
  @PostMapping("/{id}/void")
  public BillDetailResponse voidBill(@PathVariable UUID id) {
    return billReader.detail(billWriter.voidBill(id));
  }

  @Operation(summary = "Record a payment against a bill (posts Dr AP / Cr cash-clearing to the GL)")
  @PostMapping("/{id}/payments")
  public BillDetailResponse pay(
      @PathVariable UUID id,
      @Valid @RequestBody RecordPaymentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (mirroring AR's code-review C-2): a payment is money-out, so a keyless
    // request (which could silently double-post on a retry) is rejected with a 400 (ApAdvice maps
    // IllegalArgumentException).
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("the Idempotency-Key header is required for a payment");
    }
    billPaymentWriter.record(id, request.amountMinor(), request.method(), idempotencyKey);
    return billReader.detail(id);
  }
}
