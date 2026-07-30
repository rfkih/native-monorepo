package id.co.nativeapp.restaurant.promotion.projection;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection over a {@code promo_rule} row — serves both {@link
 * id.co.nativeapp.restaurant.promotion.service.PromotionEngineService PromotionEngineService}
 * resolution (the effective-rule set at checkout/pay time) and the admin listing endpoint, never
 * {@code SELECT *} of the entity (CODE-STRUCTURE.md §3.3).
 */
public interface PromoRuleView {

  UUID getId();

  String getName();

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

  int getPriority();

  boolean isExclusive();

  boolean isRequiresCoupon();

  boolean isActive();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
