package id.co.nativeapp.restaurant.promotion.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Request body to create a {@code promo_rule} row. {@code company_id} and actor are intentionally
 * absent — they come from the bound tenant scope, never the client (rule 5).
 *
 * <p>Type/parameter validation ({@code PromoRuleAdminService}, {@code PromoRuleValidationException}
 * → 422):
 *
 * <ul>
 *   <li>{@code PERCENT_OFF_ORDER}/{@code PERCENT_OFF_LINE} require {@code rateBp} (0-10000) and
 *       forbid {@code amountMinor}/{@code currency};
 *   <li>{@code AMOUNT_OFF_ORDER} requires {@code amountMinor} (&ge; 0) + {@code currency} and
 *       forbids {@code rateBp};
 *   <li>{@code PERCENT_OFF_LINE} requires {@code scopeKind} + {@code scopeRefId}; every other type
 *       forbids them (order-scope rules have no line to scope to);
 *   <li>{@code BUY_X_GET_Y} is rejected — schema-reserved, not shipped this phase (see {@link
 *       id.co.nativeapp.restaurant.promotion.domain.PromoRuleType}).
 * </ul>
 *
 * @param name display name
 * @param ruleType {@code PERCENT_OFF_ORDER} | {@code AMOUNT_OFF_ORDER} | {@code PERCENT_OFF_LINE}
 * @param scopeKind {@code ITEM} | {@code CATEGORY}; required for {@code PERCENT_OFF_LINE} only
 * @param scopeRefId the menu item/category id; required for {@code PERCENT_OFF_LINE} only
 * @param rateBp basis-point rate (0-10000); required for the two percent types
 * @param amountMinor fixed discount in minor units (&ge; 0); required for {@code AMOUNT_OFF_ORDER}
 * @param currency ISO-4217 code; required for {@code AMOUNT_OFF_ORDER}
 * @param minSubtotalMinor optional minimum order subtotal (minor units) this rule requires to
 *     qualify
 * @param dowMask optional day-of-week bitmask (bit 0 = Monday .. bit 6 = Sunday); {@code null} =
 *     every day
 * @param windowStart optional happy-hour start time (in {@code tz})
 * @param windowEnd optional happy-hour end time (in {@code tz}); an overnight window has {@code
 *     windowStart > windowEnd}
 * @param tz IANA timezone id for {@code windowStart}/{@code windowEnd}; defaults to {@code
 *     Asia/Jakarta} when omitted
 * @param priority lower = evaluated first among automatics; defaults to 100 when omitted
 * @param exclusive when {@code true}, a match stops further automatic rules from stacking
 * @param requiresCoupon when {@code true}, this rule is never picked up automatically — only via a
 *     coupon that links to it
 * @param effectiveFrom the date this rule version becomes effective; defaults to today when omitted
 * @param effectiveTo the date this rule version stops being effective (inclusive); defaults to the
 *     {@code 9999-12-31} open-ended sentinel when omitted
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record PromoRuleCreateRequest(
    @NotBlank String name,
    @NotBlank String ruleType,
    String scopeKind,
    UUID scopeRefId,
    @Min(0) Long rateBp,
    @Min(0) Long amountMinor,
    String currency,
    @Min(0) Long minSubtotalMinor,
    Short dowMask,
    LocalTime windowStart,
    LocalTime windowEnd,
    String tz,
    Integer priority,
    Boolean exclusive,
    Boolean requiresCoupon,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
