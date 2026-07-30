package id.co.nativeapp.carwash.promotion.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection joining a {@code coupon} row with its linked {@code promo_rule} row
 * — exactly what {@link id.co.nativeapp.carwash.promotion.service.PromotionEngineService
 * PromotionEngineService} needs to validate a code and (if valid) apply its rule's deduction, in
 * one round trip. Never {@code SELECT *} of either entity (CODE-STRUCTURE.md §3.3). Ported verbatim
 * from restaurant-service.
 */
public interface CouponRuleView {

  UUID getCouponId();

  String getCode();

  int getMaxRedemptions();

  int getRedeemedCount();

  @Nullable Instant getExpiresAt();

  boolean isCouponActive();

  UUID getRuleId();

  String getRuleName();

  String getRuleType();

  @Nullable String getScopeKind();

  @Nullable UUID getScopeRefId();

  @Nullable Long getRateBp();

  @Nullable Long getAmountMinor();

  @Nullable String getCurrency();

  @Nullable Long getMinSubtotalMinor();

  @Nullable Short getDowMask();

  @Nullable LocalTime getWindowStart();

  @Nullable LocalTime getWindowEnd();

  String getTz();

  boolean isRuleActive();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
