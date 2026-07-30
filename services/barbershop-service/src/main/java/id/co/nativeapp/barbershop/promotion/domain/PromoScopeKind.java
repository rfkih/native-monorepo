package id.co.nativeapp.barbershop.promotion.domain;

/**
 * What {@code promo_rule.scope_ref_id} points at for a line-scope rule ({@link
 * PromoRuleType#PERCENT_OFF_LINE} today; {@code BUY_X_GET_Y} when it ships).
 *
 * <p>Ported verbatim from restaurant-service via carwash-service (ADR 0026). {@code CATEGORY} is
 * schema-legal here (the DDL is byte-identical across verticals) but is NEVER exercised by
 * barbershop: there is no {@code menu_category}-equivalent catalog dimension for {@code
 * service_item}/{@code service_addon}, so {@link
 * id.co.nativeapp.barbershop.promotion.dto.EvalLine#categoryId() EvalLine.categoryId()} is always
 * {@code null} for a barbershop ticket line, which means a {@code CATEGORY}-scoped rule can never
 * match here — the admin API accepts creating one (schema allows it), it would simply never apply.
 * See {@link id.co.nativeapp.barbershop.promotion.service.PromotionEngineService
 * PromotionEngineService}.
 */
public enum PromoScopeKind {
  /** {@code scope_ref_id} is a {@code service_item.id} or {@code service_addon.id}. */
  ITEM,

  /** Schema-legal, never matches for barbershop (no category dimension exists) — see class javadoc. */
  CATEGORY
}
