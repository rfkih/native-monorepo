package id.co.nativeapp.restaurant.sale.dto;

import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * The application command to record a sale, assembled at the request edge from the {@code POST
 * /sales} body. The tenant ({@code company_id}) and actor are NOT here — they come from the bound
 * {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the request body (rule 5).
 *
 * <p>The bean-validation constraints reject malformed input at the controller edge (via
 * {@code @Valid}) so a bad request fails with a {@code 400} from {@link ApiExceptionHandler} rather
 * than a downstream {@code 500}. {@code amountMinor} is {@link Positive} because a sale is the
 * GRAND TOTAL the customer pays — a zero or negative grand total is not a valid sale (a fully-
 * discounted order is rejected: "a fully-comped order is not a sale"). The currency is only checked
 * for presence here; its ISO-4217 validity is enforced by {@code libs/money} {@link
 * id.co.nativeapp.money.Money} (also mapped to {@code 400}).
 *
 * @param businessId the originating business unit
 * @param amountMinor the GRAND TOTAL in the currency's minor units (never a float); must be
 *     positive — this is the customer-pays amount after discount, service charge, and tax
 * @param currency the ISO-4217 currency code
 * @param occurredAt when the sale occurred; the caller defaults a missing value to now
 * @param idempotencyKey the client's request id, the producer-idempotency dedupe key
 * @param tenderType the payment tender type ({@code CASH}, {@code QRIS}, {@code CARD}), or {@code
 *     null} for legacy/no-payment sales; threaded to the {@code SaleRecorded} event wire field so
 *     finance can route the GL clearing account by tender (ADR 0006, slice 2)
 * @param breakdown the Phase 2 price breakdown (subtotal, discount, service charge, tax); null for
 *     legacy/carwash paths (all-null breakdown fields on the wire)
 */
public record RecordSaleCommand(
    @NotNull UUID businessId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    Instant occurredAt,
    @NotBlank String idempotencyKey,
    String tenderType,
    PriceBreakdown breakdown) {

  /**
   * Convenience constructor preserving the original five-argument shape (no-tender / legacy
   * callers). Sets {@code tenderType} and {@code breakdown} to {@code null}.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey) {
    this(businessId, amountMinor, currency, occurredAt, idempotencyKey, null, null);
  }

  /**
   * Convenience constructor for callers with a tender type but no breakdown (Phase 1 / no-pricing
   * path). Sets {@code breakdown} to {@code null}.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey,
      String tenderType) {
    this(businessId, amountMinor, currency, occurredAt, idempotencyKey, tenderType, null);
  }
}
