package id.co.nativeapp.restaurant.promotion.domain;

/**
 * What {@code promo_rule.scope_ref_id} points at for a line-scope rule ({@link
 * PromoRuleType#PERCENT_OFF_LINE} today; {@code BUY_X_GET_Y} when it ships).
 */
public enum PromoScopeKind {
  /** {@code scope_ref_id} is a {@code menu_item.id}. */
  ITEM,

  /** {@code scope_ref_id} is a {@code menu_category.id} (restaurant-only dimension). */
  CATEGORY
}
