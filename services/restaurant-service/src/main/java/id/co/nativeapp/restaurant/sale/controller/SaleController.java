package id.co.nativeapp.restaurant.sale.controller;

import id.co.nativeapp.restaurant.config.DevTenantFilter;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleRequest;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
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
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

  private final SaleService saleService;

  public SaleController(SaleService saleService) {
    this.saleService = saleService;
  }

  @PostMapping
  public ResponseEntity<SaleResponse> recordSale(@Valid @RequestBody SaleRequest request) {
    RecordSaleCommand command =
        new RecordSaleCommand(
            request.businessId(),
            request.amountMinor(),
            request.currency(),
            request.occurredAt() != null ? request.occurredAt() : Instant.now(),
            request.idempotencyKey());

    RecordSaleResult result = saleService.recordSale(command);
    SaleResponse body = result.sale();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/sales/" + body.id())).body(body)
        : ResponseEntity.ok(body);
  }
}
