package id.co.nativeapp.restaurant.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code POST /api/v1/ingredients/{id}/stock/add}.
 *
 * <p>{@code amount} is the signed delta to add to the current stock. Positive values receive stock;
 * negative values manually reduce stock (floored at 0 — cannot go negative).
 *
 * <p>{@code amountPaidMinor} + {@code costCurrency} are OPTIONAL and both-or-neither: when present
 * (only valid on a positive receive) the call is a PRICED purchase that updates the moving
 * weighted-average cost (V36) — {@code amountPaidMinor} is the TOTAL paid for this receipt, in the
 * currency's minor units (never a per-unit price, so the average stays exact). When absent the call
 * is a costless quantity adjustment that preserves the current unit cost. The both-or-neither and
 * positive-amount rules are enforced in the service/aggregate (400 on violation), mirroring how
 * {@code CreateIngredientRequest}'s cost pair is validated in the domain.
 */
public record AddIngredientStockRequest(
    @NotNull Integer amount,
    @Nullable @PositiveOrZero Long amountPaidMinor,
    @Nullable @Size(min = 3, max = 3) String costCurrency) {}
