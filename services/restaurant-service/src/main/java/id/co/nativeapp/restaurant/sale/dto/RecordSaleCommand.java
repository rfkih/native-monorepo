package id.co.nativeapp.restaurant.sale.dto;

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
 * than a downstream {@code 500}. {@code amountMinor} is {@link Positive} because a sale is positive
 * revenue — a zero or negative amount is not a valid sale. The currency is only checked for
 * presence here; its ISO-4217 validity is enforced by {@code libs/money} {@link
 * id.co.nativeapp.money.Money} (also mapped to {@code 400}).
 *
 * @param businessId the originating business unit
 * @param amountMinor the amount in the currency's minor units (never a float); must be positive
 * @param currency the ISO-4217 currency code
 * @param occurredAt when the sale occurred; the caller defaults a missing value to now
 * @param idempotencyKey the client's request id, the producer-idempotency dedupe key
 * @param tenderType the payment tender type ({@code CASH}, {@code QRIS}, {@code CARD}), or {@code
 *     null} for legacy/no-payment sales; threaded to the {@code SaleRecorded} event wire field so
 *     finance can route the GL clearing account by tender (ADR 0006, slice 2)
 */
public record RecordSaleCommand(
    @NotNull UUID businessId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    Instant occurredAt,
    @NotBlank String idempotencyKey,
    String tenderType) {

  /**
   * Convenience constructor preserving the original five-argument shape (no-tender / legacy
   * callers). Sets {@code tenderType} to {@code null}, which the event catalog defines as "no
   * tender / carwash / legacy" and finance maps to the {@code CASH_CLEARING} account.
   */
  public RecordSaleCommand(
      UUID businessId,
      Long amountMinor,
      String currency,
      Instant occurredAt,
      String idempotencyKey) {
    this(businessId, amountMinor, currency, occurredAt, idempotencyKey, null);
  }
}
