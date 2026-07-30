package id.co.nativeapp.restaurant.giftcard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/gift-card-sales} — sells (mints) a new gift card at the till
 * (ADR 0027, Phase 4). {@code company_id} and the actor are intentionally absent — they come from
 * the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never the client (rule 5).
 * A till operation: {@link id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard
 * OutletAccessGuard} is enforced, but no owner/manager role is required (selling a card is not a
 * discount/discretionary action).
 *
 * @param businessId the outlet selling the card
 * @param idempotencyKey the client's request id (producer-idempotency dedupe key)
 * @param amountMinor the stored-value amount to load onto the new card, in minor units; must be
 *     positive (rule 8 — never a float)
 * @param currency ISO-4217 currency code
 * @param tenderType the tender used to purchase the card: {@code CASH}/{@code QRIS}/{@code CARD}.
 *     {@code @NotNull} (security review W-3) — an unspecified tender used to route to CASH clearing
 *     by default, which silently mis-books a digital-tender sale as cash; the till must now name
 *     the tender explicitly.
 */
public record SellGiftCardRequest(
    @NotNull UUID businessId,
    @NotBlank String idempotencyKey,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    @NotNull String tenderType) {}
