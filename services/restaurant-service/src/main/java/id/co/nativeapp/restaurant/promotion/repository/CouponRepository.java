package id.co.nativeapp.restaurant.promotion.repository;

import id.co.nativeapp.restaurant.promotion.domain.Coupon;
import id.co.nativeapp.restaurant.promotion.projection.CouponRuleView;
import id.co.nativeapp.restaurant.promotion.projection.CouponView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Coupon}. All queries are native (rule 10 / CODE-STRUCTURE.md
 * §3.3). The RLS policy on {@code coupon} (and the joined {@code promo_rule}) scopes every query to
 * the bound tenant automatically — no manual {@code WHERE company_id} is required.
 */
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

  /**
   * Resolves a normalized (uppercase, trimmed) coupon code together with its linked rule's fields
   * in one round trip — what {@link
   * id.co.nativeapp.restaurant.promotion.service.PromotionEngineService PromotionEngineService}
   * needs to validate and apply a coupon's deduction.
   */
  @Query(
      value =
          """
          SELECT c.id                  AS couponId,
                 c.code                AS code,
                 c.max_redemptions     AS maxRedemptions,
                 c.redeemed_count      AS redeemedCount,
                 c.expires_at          AS expiresAt,
                 c.active              AS couponActive,
                 r.id                  AS ruleId,
                 r.name                AS ruleName,
                 r.rule_type           AS ruleType,
                 r.scope_kind          AS scopeKind,
                 r.scope_ref_id        AS scopeRefId,
                 r.rate_bp             AS rateBp,
                 r.amount_minor        AS amountMinor,
                 r.currency            AS currency,
                 r.min_subtotal_minor  AS minSubtotalMinor,
                 r.dow_mask            AS dowMask,
                 r.window_start        AS windowStart,
                 r.window_end          AS windowEnd,
                 r.tz                  AS tz,
                 r.active              AS ruleActive,
                 r.effective_from      AS effectiveFrom,
                 r.effective_to        AS effectiveTo
            FROM coupon c
            JOIN promo_rule r ON r.id = c.rule_id
           WHERE c.code = :code
          """,
      nativeQuery = true)
  Optional<CouponRuleView> findViewByCode(@Param("code") String code);

  /** Every coupon for the admin listing endpoint, optionally restricted to active-only. */
  @Query(
      value =
          """
          SELECT id                AS id,
                 code              AS code,
                 rule_id           AS ruleId,
                 max_redemptions   AS maxRedemptions,
                 redeemed_count    AS redeemedCount,
                 expires_at        AS expiresAt,
                 active            AS active
            FROM coupon
           WHERE (:activeOnly = FALSE OR active = TRUE)
           ORDER BY code ASC
          """,
      nativeQuery = true)
  List<CouponView> findViews(@Param("activeOnly") boolean activeOnly);

  /**
   * THE single-use / max-redemption enforcement idiom (V16 migration comment) — one atomic UPDATE
   * that is simultaneously the guard and the increment. Zero rows updated means the coupon was
   * already exhausted or inactive (possibly a concurrent checkout that got there first); the caller
   * maps that to {@link id.co.nativeapp.restaurant.promotion.domain.CouponExhaustedException}
   * (409).
   *
   * <p>Must be called inside the caller's already-open {@code @Transactional} write (the
   * surrounding {@code OrderWriter}/{@code BillWriter} REQUIRES_NEW method) so a rollback (e.g. a
   * later stock shortfall) also reverts this increment — exactly the {@code
   * MenuItemRepository#deductStock} pattern.
   *
   * @return the number of rows updated: 1 on success, 0 if the coupon is exhausted or inactive
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE coupon
             SET redeemed_count = redeemed_count + 1,
                 updated_at     = NOW(),
                 version        = version + 1
           WHERE id             = :id
             AND active         = TRUE
             AND redeemed_count < max_redemptions
          """,
      nativeQuery = true)
  int redeemIfAvailable(@Param("id") UUID id);
}
