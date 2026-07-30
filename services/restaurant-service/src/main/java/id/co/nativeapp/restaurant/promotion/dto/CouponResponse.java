package id.co.nativeapp.restaurant.promotion.dto;

import java.time.Instant;
import java.util.UUID;

/** Wire shape for a {@code coupon} row (admin list/create/patch responses). */
public record CouponResponse(
    UUID id,
    String code,
    UUID ruleId,
    int maxRedemptions,
    int redeemedCount,
    Instant expiresAt,
    boolean active) {}
