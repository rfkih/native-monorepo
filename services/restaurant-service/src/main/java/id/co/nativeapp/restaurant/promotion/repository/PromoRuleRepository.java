package id.co.nativeapp.restaurant.promotion.repository;

import id.co.nativeapp.restaurant.promotion.domain.PromoRule;
import id.co.nativeapp.restaurant.promotion.projection.PromoRuleView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PromoRule}. All queries are native (rule 10 / CODE-STRUCTURE.md
 * §3.3). The RLS policy on {@code promo_rule} scopes every query to the bound tenant automatically —
 * no manual {@code WHERE company_id} is required.
 */
public interface PromoRuleRepository extends JpaRepository<PromoRule, UUID> {

  /**
   * The active, currently-effective rule set at {@code asOf}, ordered {@code priority ASC, id ASC}
   * for deterministic composition ({@link
   * id.co.nativeapp.restaurant.promotion.service.PromotionEngineService PromotionEngineService} relies on this
   * ordering — lower priority number evaluated first among automatics; ties break on id).
   *
   * @param asOf the date the order/check occurred on (UTC date of {@code occurredAt})
   */
  @Query(
      value =
          """
          SELECT id                  AS id,
                 name                AS name,
                 rule_type           AS ruleType,
                 scope_kind          AS scopeKind,
                 scope_ref_id        AS scopeRefId,
                 rate_bp             AS rateBp,
                 amount_minor        AS amountMinor,
                 currency            AS currency,
                 min_subtotal_minor  AS minSubtotalMinor,
                 dow_mask            AS dowMask,
                 window_start        AS windowStart,
                 window_end          AS windowEnd,
                 tz                  AS tz,
                 priority            AS priority,
                 exclusive           AS exclusive,
                 requires_coupon     AS requiresCoupon,
                 active              AS active,
                 effective_from      AS effectiveFrom,
                 effective_to        AS effectiveTo
            FROM promo_rule
           WHERE active         = TRUE
             AND effective_from <= :asOf
             AND effective_to   >= :asOf
           ORDER BY priority ASC, id ASC
          """,
      nativeQuery = true)
  List<PromoRuleView> findActiveEffective(@Param("asOf") LocalDate asOf);

  /**
   * Every rule for the admin listing endpoint, optionally restricted to active-only, ordered by
   * priority then name.
   */
  @Query(
      value =
          """
          SELECT id                  AS id,
                 name                AS name,
                 rule_type           AS ruleType,
                 scope_kind          AS scopeKind,
                 scope_ref_id        AS scopeRefId,
                 rate_bp             AS rateBp,
                 amount_minor        AS amountMinor,
                 currency            AS currency,
                 min_subtotal_minor  AS minSubtotalMinor,
                 dow_mask            AS dowMask,
                 window_start        AS windowStart,
                 window_end          AS windowEnd,
                 tz                  AS tz,
                 priority            AS priority,
                 exclusive           AS exclusive,
                 requires_coupon     AS requiresCoupon,
                 active              AS active,
                 effective_from      AS effectiveFrom,
                 effective_to        AS effectiveTo
            FROM promo_rule
           WHERE (:activeOnly = FALSE OR active = TRUE)
           ORDER BY priority ASC, name ASC
          """,
      nativeQuery = true)
  List<PromoRuleView> findViews(@Param("activeOnly") boolean activeOnly);
}
