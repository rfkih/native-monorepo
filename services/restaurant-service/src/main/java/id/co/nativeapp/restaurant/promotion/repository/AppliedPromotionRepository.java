package id.co.nativeapp.restaurant.promotion.repository;

import id.co.nativeapp.restaurant.promotion.domain.AppliedPromotion;
import id.co.nativeapp.restaurant.promotion.projection.AppliedPromotionView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link AppliedPromotion}. Writes go through the inherited {@code
 * save}/{@code saveAll} (the write path needs the whole aggregate); the native-query read below is
 * reserved for future reporting (receipt rendering, promo-performance dashboards).
 *
 * <p>All queries are native (rule 10 / CODE-STRUCTURE.md §3.3). The RLS policy on {@code
 * applied_promotion} scopes every query to the bound tenant automatically — no manual {@code WHERE
 * company_id} is required.
 */
public interface AppliedPromotionRepository extends JpaRepository<AppliedPromotion, UUID> {

  /** The applied-promotion audit rows for a given sale (receipt / finance-adjacent audit query). */
  @Query(
      value =
          """
          SELECT id                  AS id,
                 order_id            AS orderId,
                 sale_id             AS saleId,
                 rule_id             AS ruleId,
                 coupon_id           AS couponId,
                 rule_name_snapshot  AS ruleNameSnapshot,
                 rule_type_snapshot  AS ruleTypeSnapshot,
                 rate_bp_snapshot    AS rateBpSnapshot,
                 line_ref            AS lineRef,
                 amount_minor        AS amountMinor,
                 currency            AS currency
            FROM applied_promotion
           WHERE sale_id = :saleId
           ORDER BY created_at
          """,
      nativeQuery = true)
  List<AppliedPromotionView> findViewsBySaleId(@Param("saleId") UUID saleId);

  /**
   * The applied-promotion audit rows for a given order (Phase 3 review fix, W1) — used at
   * digital-tender capture time to reconstruct the Phase-2 {@link
   * id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown} the order priced at checkout. Each
   * row's {@code amount_minor} is already the CLAMPED rule/coupon-backed deduction (V16 migration
   * comment: "the actual minor-unit amount THIS rule discounted off THIS order, already clamped per
   * the composition rule"), so summing them recovers the rule-based portion of the checkout-time
   * discount exactly. Queried by {@code order_id} rather than {@code sale_id} because at capture
   * time — before the sale is recorded — every row for a digital-tender order still carries {@code
   * sale_id = NULL}.
   */
  @Query(
      value =
          """
          SELECT id                  AS id,
                 order_id            AS orderId,
                 sale_id             AS saleId,
                 rule_id             AS ruleId,
                 coupon_id           AS couponId,
                 rule_name_snapshot  AS ruleNameSnapshot,
                 rule_type_snapshot  AS ruleTypeSnapshot,
                 rate_bp_snapshot    AS rateBpSnapshot,
                 line_ref            AS lineRef,
                 amount_minor        AS amountMinor,
                 currency            AS currency
            FROM applied_promotion
           WHERE order_id = :orderId
           ORDER BY created_at
          """,
      nativeQuery = true)
  List<AppliedPromotionView> findViewsByOrderId(@Param("orderId") UUID orderId);

  /**
   * Stamps {@code sale_id} onto every still-{@code NULL} row for {@code orderId} — the
   * digital-tender capture path (ADR 0006 revenue-at-capture), where the order/check's deductions
   * were written at checkout/pay time with {@code sale_id = NULL} because no sale existed yet.
   * Called from {@code PaymentCaptureWriter.capture} once the sale is recorded, in the same
   * transaction.
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE applied_promotion
             SET sale_id    = :saleId,
                 updated_at = NOW(),
                 version    = version + 1
           WHERE order_id   = :orderId
             AND sale_id IS NULL
          """,
      nativeQuery = true)
  int stampSaleId(@Param("orderId") UUID orderId, @Param("saleId") UUID saleId);
}
