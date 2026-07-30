package id.co.nativeapp.barbershop.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * Request body to create a {@code coupon} row. {@code company_id} and actor are intentionally
 * absent — they come from the bound tenant scope, never the client (rule 5). Ported verbatim from
 * restaurant-service via carwash-service.
 *
 * <p>{@code code} is trimmed and uppercased by the service before insert (so {@code "save10"} and
 * {@code "SAVE10"} are the same coupon); a duplicate {@code (company_id, code)} surfaces as {@code
 * 409 Conflict}. {@code ruleId} must reference an existing, visible {@code promo_rule} — an unknown
 * id is a {@link id.co.nativeapp.barbershop.promotion.domain.PromoRuleValidationException} (422).
 *
 * @param code the redemption code (case-insensitive; normalized to uppercase)
 * @param ruleId the {@link id.co.nativeapp.barbershop.promotion.domain.PromoRule} this coupon
 *     redeems — may be of ANY rule type, including a {@code requires_coupon = TRUE} one
 * @param maxRedemptions maximum number of times this coupon may be redeemed; must be &gt; 0; defaults
 *     to 1 (single-use) when omitted
 * @param expiresAt optional expiry instant; {@code null} means the coupon never expires
 */
public record CouponCreateRequest(
    @NotBlank String code, @NotNull UUID ruleId, @Positive Integer maxRedemptions, Instant expiresAt) {}
