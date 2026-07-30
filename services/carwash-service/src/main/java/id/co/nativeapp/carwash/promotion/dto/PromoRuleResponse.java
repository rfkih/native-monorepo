package id.co.nativeapp.carwash.promotion.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Wire shape for a {@code promo_rule} row (admin list/create/patch responses). Mapped from the
 * write-path entity ({@code PromotionAdminWriter}) or the read-path {@code PromoRuleView} projection
 * ({@code PromotionAdminReader}) — never here, since a {@code dto} may not depend on the {@code
 * projection} layer (CODE-STRUCTURE.md §6). Ported verbatim from restaurant-service.
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record PromoRuleResponse(
    UUID id,
    String name,
    String ruleType,
    String scopeKind,
    UUID scopeRefId,
    Long rateBp,
    Long amountMinor,
    String currency,
    Long minSubtotalMinor,
    Short dowMask,
    LocalTime windowStart,
    LocalTime windowEnd,
    String tz,
    int priority,
    boolean exclusive,
    boolean requiresCoupon,
    boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
