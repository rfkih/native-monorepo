package id.co.nativeapp.carwash.ticket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/carwash/tickets/checkout} request body. {@code company_id} and the actor are
 * intentionally absent — they come from the bound tenant scope, never the client (rule 5). Server-
 * side pricing means the client never supplies an amount; only item references + quantities.
 *
 * @param businessId the carwash outlet the ticket was opened at
 * @param idempotencyKey the client's request id (producer-idempotency dedupe key)
 * @param bay the wash bay it ran on
 * @param vehiclePlate the optional vehicle plate; {@code null} for not recorded
 * @param staffProfileId the optional washer staff profile selected at checkout
 * @param discountMinor an optional fixed discount in minor units; {@code null} for no discount. This
 *     is ONE INPUT to the Phase-3 promotions engine (ADR 0026) — the manual-discount layer, applied
 *     LAST after every automatic rule and any redeemed coupon, then jointly clamped so the total
 *     discount can never exceed the ticket subtotal. A positive value requires the {@code owner}/
 *     {@code manager} role ({@code ManualDiscountGuard}) — a cashier/attendant token is rejected
 *     with {@code 403}.
 * @param lines the requested lines; must be non-empty
 * @param payment the tender
 * @param couponCode Phase 3 (ADR 0026): optional coupon code; case-insensitive. An invalid or
 *     exhausted code is rejected ({@code 422}/{@code 409}) — unlike the quote endpoint, checkout is
 *     money-moving and never silently drops a bad code. {@code null}/blank means no coupon.
 */
public record CheckoutRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotBlank String bay,
    String vehiclePlate,
    UUID staffProfileId,
    @PositiveOrZero Long discountMinor,
    @NotEmpty List<@Valid TicketLineInput> lines,
    @NotNull @Valid PaymentRequest payment,
    String couponCode) {

  /** Convenience for the pre-Phase-3 eight-arg shape (no coupon code). */
  public CheckoutRequest(
      UUID businessId,
      String idempotencyKey,
      String bay,
      String vehiclePlate,
      UUID staffProfileId,
      Long discountMinor,
      List<TicketLineInput> lines,
      PaymentRequest payment) {
    this(businessId, idempotencyKey, bay, vehiclePlate, staffProfileId, discountMinor, lines, payment, null);
  }
}
