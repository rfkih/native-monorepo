package id.co.nativeapp.carwash.promotion.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for a {@code coupon} row (admin list/create/patch responses). Ported verbatim from
 * restaurant-service.
 */
public record CouponResponse(
    UUID id,
    String code,
    UUID ruleId,
    int maxRedemptions,
    int redeemedCount,
    Instant expiresAt,
    boolean active) {}
