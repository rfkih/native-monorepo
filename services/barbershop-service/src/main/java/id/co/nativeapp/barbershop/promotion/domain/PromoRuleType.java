package id.co.nativeapp.barbershop.promotion.domain;

/**
 * The kind of discount a {@link PromoRule} row applies (ADR 0026). Ported verbatim from
 * restaurant-service via carwash-service.
 *
 * <p><strong>{@code BUY_X_GET_Y} is deliberately EXCLUDED from this Java enum.</strong> The {@code
 * promo_rule.rule_type} database CHECK constraint (V3) already allows it — the supporting columns
 * ({@code buy_qty}/{@code get_qty}/{@code get_scope_ref_id}) are schema-reserved so a future engine
 * revision can add it without another migration — but the Phase-3 {@link
 * id.co.nativeapp.barbershop.promotion.service.PromotionEngineService PromotionEngineService} ships
 * WITHOUT it: no admin create/patch path accepts it, and no evaluator branch handles it. Mapping
 * this enum with {@code @Enumerated(EnumType.STRING)} means a {@code BUY_X_GET_Y} row (which this
 * service never writes) would fail to hydrate — acceptable, since nothing in this slice ever
 * persists one.
 */
public enum PromoRuleType {
  /** A basis-point percent off the whole ticket subtotal (e.g. 10% off). */
  PERCENT_OFF_ORDER,

  /** A fixed {@code Money} amount off the whole ticket subtotal. */
  AMOUNT_OFF_ORDER,

  /** A basis-point percent off a single matching line (scoped to one item — CATEGORY never matches). */
  PERCENT_OFF_LINE
}
