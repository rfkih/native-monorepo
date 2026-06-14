package id.co.nativeapp.restaurant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
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

  /**
   * Record-sale request body. {@code occurredAt} is optional (defaults to now); the monetary amount
   * is integer {@code amountMinor} + ISO-4217 {@code currency} (never a float). {@code company_id}
   * is intentionally absent — it is taken from the tenant scope, not trusted from the client.
   *
   * <p>The bean-validation constraints are checked by {@code @Valid} on the handler param: a
   * missing/blank/non-positive field fails fast with a {@code 400} from {@link ApiExceptionHandler}
   * (a {@link org.springframework.web.bind.MethodArgumentNotValidException
   * MethodArgumentNotValidException}) instead of reaching the service. {@code amountMinor} must be
   * {@link Positive} — a sale is positive revenue. The same constraints are mirrored on {@link
   * RecordSaleCommand} so the application command is valid by construction even when assembled
   * outside this controller.
   */
  public record SaleRequest(
      @NotNull UUID businessId,
      @NotNull @Positive Long amountMinor,
      @NotBlank String currency,
      Instant occurredAt,
      @NotBlank String idempotencyKey) {}

  /** Record-sale response body. */
  public record SaleResponse(
      UUID id,
      UUID businessId,
      long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey) {

    static SaleResponse from(Sale sale) {
      return new SaleResponse(
          sale.getId(),
          sale.getBusinessId(),
          sale.getAmount().amountMinor(),
          sale.getAmount().currency().getCurrencyCode(),
          sale.getOccurredAt(),
          sale.getIdempotencyKey());
    }
  }
}
