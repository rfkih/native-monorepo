package id.co.nativeapp.finance.tax.controller;

import id.co.nativeapp.finance.tax.dto.EfakturExportResponse;
import id.co.nativeapp.finance.tax.dto.FileReturnRequest;
import id.co.nativeapp.finance.tax.dto.TaxFilingHistoryResponse;
import id.co.nativeapp.finance.tax.dto.TaxFilingResponse;
import id.co.nativeapp.finance.tax.dto.VatReturnResponse;
import id.co.nativeapp.finance.tax.service.FileReturnResult;
import id.co.nativeapp.finance.tax.service.TaxFilingReader;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.finance.tax.service.VatReturnReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/tax} — the PPN (VAT) return surface for the bound tenant: the GL-derived VAT report,
 * filing a return (posts the period-end netting entry + seals the period), the filing history +
 * detail, settling a net-payable return (posts the payment), and the e-Faktur CSV export. Writes go
 * through the RLS-bound service; every response is a DTO (never the entity). Typed faults map to
 * RFC-7807 via {@link TaxAdvice} (404 not-found, 409 invalid-state, 400 bad-input/no-activity, 422
 * currency-mismatch).
 *
 * <p><strong>ILLUSTRATIVE / SME-gated:</strong> the 11% rate, the net-creditable carryforward policy,
 * PKP status, and the e-Faktur layout are placeholders — see ADR 0017 + V31/V32 headers.
 */
@Tag(name = "Tax / PPN", description = "PPN (VAT) return, filing, settlement + e-Faktur for the tenant.")
@RestController
@RequestMapping("/api/v1/tax")
@Validated
public class TaxController {

  private final VatReturnReader vatReturnReader;
  private final TaxFilingService taxFilingService;
  private final TaxFilingReader taxFilingReader;

  public TaxController(
      VatReturnReader vatReturnReader,
      TaxFilingService taxFilingService,
      TaxFilingReader taxFilingReader) {
    this.vatReturnReader = vatReturnReader;
    this.taxFilingService = taxFilingService;
    this.taxFilingReader = taxFilingReader;
  }

  @Operation(summary = "The PPN (VAT) return for a period (output − input = net payable/creditable)")
  @GetMapping("/vat/return")
  public VatReturnResponse vatReturn(
      @RequestParam
          @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "period must be a valid YYYY-MM month")
          String period) {
    return vatReturnReader.read(period);
  }

  @Operation(summary = "File the PPN return for a period (posts the netting entry, seals the period)")
  @PostMapping("/vat/returns")
  public ResponseEntity<TaxFilingResponse> file(@Valid @RequestBody FileReturnRequest request) {
    FileReturnResult result = taxFilingService.file(request.period());
    TaxFilingResponse detail = taxFilingReader.detail(result.filingId());
    // Idempotent-POST contract (ENGINEERING-STANDARDS §1.1): a fresh file → 201 + Location; a
    // re-file of an already-filed period → 200 with the existing resource (posting nothing), like
    // WithinCompanyCloseController's re-close.
    if (!result.created()) {
      return ResponseEntity.ok(detail);
    }
    return ResponseEntity.created(URI.create("/api/v1/tax/vat/returns/" + detail.id())).body(detail);
  }

  @Operation(summary = "The PPN filing history for the bound tenant (most-recent period first)")
  @GetMapping("/vat/returns")
  public TaxFilingHistoryResponse history() {
    return taxFilingReader.history();
  }

  @Operation(summary = "A filed PPN return by id")
  @GetMapping("/vat/returns/{id}")
  public TaxFilingResponse get(@PathVariable UUID id) {
    return taxFilingReader.detail(id);
  }

  @Operation(summary = "Settle a filed net-payable return (posts Dr VAT payable / Cr cash-clearing)")
  @PostMapping("/vat/returns/{id}/settle")
  public TaxFilingResponse settle(@PathVariable UUID id) {
    return taxFilingReader.detail(taxFilingService.settle(id));
  }

  @Operation(summary = "e-Faktur export of the period's output tax invoices (illustrative; real DJP deferred)")
  @GetMapping("/vat/efaktur")
  public EfakturExportResponse efaktur(
      @RequestParam
          @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "period must be a valid YYYY-MM month")
          String period) {
    return taxFilingReader.efaktur(period);
  }
}
