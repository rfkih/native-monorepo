package id.co.nativeapp.restaurant.sale;

import id.co.nativeapp.restaurant.config.DevTenantFilter;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /sales} — record a sale.
 *
 * <p>The request carries only the business payload; the tenant ({@code company_id}) and actor come
 * from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request
 * edge by {@link DevTenantFilter}, the documented stand-in for the JWT/gateway that arrives in
 * M1.1) — never from the body (rule 5).
 *
 * <p>Returns {@code 201 Created} when a new sale was recorded (and exactly one {@code SaleRecorded}
 * emitted), and {@code 200 OK} when a retry with the same {@code idempotency_key} returned the
 * pre-existing sale (no second event).
 */
@RestController
public class SaleController {

  private final SaleService saleService;

  public SaleController(SaleService saleService) {
    this.saleService = saleService;
  }

  @PostMapping("/sales")
  public ResponseEntity<SaleResponse> recordSale(@Valid @RequestBody SaleRequest request) {
    RecordSaleCommand command =
        new RecordSaleCommand(
            request.businessId(),
            request.amountMinor(),
            request.currency(),
            request.occurredAt() != null ? request.occurredAt() : Instant.now(),
            request.idempotencyKey());

    RecordSaleResult result = saleService.recordSale(command);
    SaleResponse body = SaleResponse.from(result.sale());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
  }
}
