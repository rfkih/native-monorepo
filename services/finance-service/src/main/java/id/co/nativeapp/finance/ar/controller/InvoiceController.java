package id.co.nativeapp.finance.ar.controller;

import id.co.nativeapp.finance.ar.dto.CreateInvoiceRequest;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceSummaryResponse;
import id.co.nativeapp.finance.ar.dto.IssueInvoiceRequest;
import id.co.nativeapp.finance.ar.dto.RecordPaymentRequest;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceReader;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.ar.service.PaymentWriter;
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
 * {@code /api/v1/invoices} — the AR invoice lifecycle for the bound tenant: create draft, list,
 * detail, issue, void, and record a payment. Writes go through the RLS-bound writers;
 * issue/void/pay post to the double-entry GL in the same transaction. Every response is a DTO
 * (never the entity); mutating endpoints return the fresh invoice detail. Typed faults map to
 * RFC-7807 via {@link ArAdvice} (404 not-found, 409 invalid-state, 400 bad-input).
 */
@Tag(
    name = "Invoices",
    description = "AR invoices for the bound tenant (draft → issue → paid/void).")
@RestController
@RequestMapping("/api/v1/invoices")
@Validated
public class InvoiceController {

  private final InvoiceWriter invoiceWriter;
  private final PaymentWriter paymentWriter;
  private final InvoiceReader invoiceReader;

  public InvoiceController(
      InvoiceWriter invoiceWriter, PaymentWriter paymentWriter, InvoiceReader invoiceReader) {
    this.invoiceWriter = invoiceWriter;
    this.paymentWriter = paymentWriter;
    this.invoiceReader = invoiceReader;
  }

  @Operation(summary = "Create a draft invoice (server computes totals + illustrative tax)")
  @PostMapping
  public ResponseEntity<InvoiceDetailResponse> create(
      @Valid @RequestBody CreateInvoiceRequest request) {
    List<InvoiceLineInput> lines =
        request.lines().stream()
            .map(l -> new InvoiceLineInput(l.description(), l.quantity(), l.unitPriceMinor()))
            .toList();
    UUID id =
        invoiceWriter.createDraft(
            request.customerId(), request.currency(), request.taxable(), lines);
    InvoiceDetailResponse detail = invoiceReader.detail(id);
    return ResponseEntity.created(URI.create("/api/v1/invoices/" + detail.id())).body(detail);
  }

  @Operation(summary = "List invoices, optionally filtered by status / customer / issue-month")
  @GetMapping
  public List<InvoiceSummaryResponse> list(
      @RequestParam(required = false)
          @Pattern(
              regexp = "DRAFT|ISSUED|PARTIALLY_PAID|PAID|VOID",
              message = "status must be a valid invoice status")
          String status,
      @RequestParam(required = false) UUID customerId,
      @RequestParam(required = false)
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    return invoiceReader.list(status, customerId, period);
  }

  @Operation(summary = "Get an invoice detail (header + lines + payments)")
  @GetMapping("/{id}")
  public InvoiceDetailResponse get(@PathVariable UUID id) {
    return invoiceReader.detail(id);
  }

  @Operation(summary = "Issue a draft invoice (posts Dr AR / Cr revenue (+ VAT) to the GL)")
  @PostMapping("/{id}/issue")
  public InvoiceDetailResponse issue(
      @PathVariable UUID id, @Valid @RequestBody(required = false) IssueInvoiceRequest request) {
    Integer termDays = request == null ? null : request.termDays();
    return invoiceReader.detail(invoiceWriter.issue(id, termDays));
  }

  @Operation(summary = "Void an invoice (posts the contra GL entry for an issued invoice)")
  @PostMapping("/{id}/void")
  public InvoiceDetailResponse voidInvoice(@PathVariable UUID id) {
    return invoiceReader.detail(invoiceWriter.voidInvoice(id));
  }

  @Operation(summary = "Record a payment against an invoice (posts Dr cash / Cr AR to the GL)")
  @PostMapping("/{id}/payments")
  public InvoiceDetailResponse pay(
      @PathVariable UUID id,
      @Valid @RequestBody RecordPaymentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (code-review C-2): a payment is money-in, so a keyless request (which
    // could
    // silently double-post on a retry) is rejected with a 400 (ArAdvice maps
    // IllegalArgumentException).
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("the Idempotency-Key header is required for a payment");
    }
    paymentWriter.record(id, request.amountMinor(), request.method(), idempotencyKey);
    return invoiceReader.detail(id);
  }
}
