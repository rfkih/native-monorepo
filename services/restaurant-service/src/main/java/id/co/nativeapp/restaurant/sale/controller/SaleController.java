package id.co.nativeapp.restaurant.sale.controller;

import id.co.nativeapp.restaurant.config.DevTenantFilter;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleRequest;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/sales} — record a sale.
 *
 * <p>The request carries only the business payload; the tenant ({@code company_id}) and actor come
 * from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request
 * edge by {@link DevTenantFilter}, the documented stand-in for the JWT/gateway that arrives in
 * M1.1) — never from the body (rule 5).
 *
 * <p>The resource lives under {@code /api/v1} (URI versioning — ENGINEERING-STANDARDS §1.1), so the
 * gateway, which routes {@code /api/v1/sales/**} preserving the full path, reaches this handler.
 *
 * <p>Returns {@code 201 Created} (with a {@code Location} header pointing at the new resource) when
 * a new sale was recorded (and exactly one {@code SaleRecorded} emitted), and {@code 200 OK} when a
 * retry with the same {@code idempotency_key} returned the pre-existing sale (no second event, no
 * {@code Location}).
 */
@Tag(name = "Sales", description = "Record a sale and retrieve sale resources")
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

  private final SaleService saleService;

  public SaleController(SaleService saleService) {
    this.saleService = saleService;
  }

  @Operation(
      summary = "Record a sale",
      description =
          "Records a new sale for the bound tenant and business. The tenant (company_id) and actor"
              + " come from TenantContext, never from the body. Returns 201 Created with a Location"
              + " header on success, or 200 OK when a retry with the same idempotency_key returns"
              + " the pre-existing sale (no second SaleRecorded event emitted).")
  @PostMapping
  public ResponseEntity<SaleResponse> recordSale(@Valid @RequestBody SaleRequest request) {
    // occurredAt is passed through AS-IS (possibly null): SaleWriter.create resolves a missing
    // value to Instant.now() itself, INSIDE its transaction, after acquiring the per-business
    // CashWindowLock (verified HIGH race fix) — resolving it here, before the transaction/lock,
    // would let a stale pre-lock instant land on a sale that had to wait behind a register close.
    RecordSaleCommand command =
        new RecordSaleCommand(
            request.businessId(),
            request.amountMinor(),
            request.currency(),
            request.occurredAt(),
            request.idempotencyKey());

    RecordSaleResult result = saleService.recordSale(command);
    SaleResponse body = result.sale();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/sales/" + body.id())).body(body)
        : ResponseEntity.ok(body);
  }
}
