package id.co.nativeapp.barbershop.promotion.dto;

import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * Partial-update request body for a {@code coupon} row. Every field is OPTIONAL — a {@code null}
 * field leaves that attribute unchanged. {@code code} and {@code ruleId} cannot be changed via PATCH
 * (re-create instead — a coupon's identity and what it redeems are immutable). Ported verbatim from
 * restaurant-service via carwash-service.
 *
 * @param maxRedemptions new redemption limit, or {@code null} to leave unchanged; must be &gt; 0 and
 *     must not fall below the current {@code redeemed_count} (rejected as 422 otherwise)
 * @param expiresAt new expiry instant, or {@code null} to leave unchanged
 * @param active new active flag, or {@code null} to leave unchanged — the primary toggle
 */
public record CouponPatchRequest(@Positive Integer maxRedemptions, Instant expiresAt, Boolean active) {}
