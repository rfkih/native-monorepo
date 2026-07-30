package id.co.nativeapp.restaurant.promotion.dto;

import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Partial-update request body for a {@code promo_rule} row. Every field is OPTIONAL — a {@code null}
 * field leaves that attribute unchanged (the {@code CatalogItemPatchRequest} convention already used
 * by carwash). {@code ruleType} cannot be changed via PATCH (re-create instead); the type/parameter
 * cross-checks in {@link PromoRuleCreateRequest}'s javadoc are re-validated against the resulting
 * combination when a parameter that participates in them is supplied.
 *
 * @param name new name, or {@code null} to leave unchanged
 * @param scopeKind new scope kind, or {@code null} to leave unchanged
 * @param scopeRefId new scope ref id, or {@code null} to leave unchanged
 * @param rateBp new basis-point rate, or {@code null} to leave unchanged
 * @param amountMinor new fixed amount, or {@code null} to leave unchanged
 * @param currency new currency, or {@code null} to leave unchanged
 * @param minSubtotalMinor new minimum-subtotal gate, or {@code null} to leave unchanged
 * @param dowMask new day-of-week mask, or {@code null} to leave unchanged
 * @param windowStart new happy-hour start, or {@code null} to leave unchanged
 * @param windowEnd new happy-hour end, or {@code null} to leave unchanged
 * @param tz new timezone, or {@code null} to leave unchanged
 * @param priority new priority, or {@code null} to leave unchanged
 * @param exclusive new exclusive flag, or {@code null} to leave unchanged
 * @param active new active flag, or {@code null} to leave unchanged — the primary toggle
 * @param effectiveFrom new effective-from date, or {@code null} to leave unchanged
 * @param effectiveTo new effective-to date, or {@code null} to leave unchanged
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record PromoRulePatchRequest(
    String name,
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
    Boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
