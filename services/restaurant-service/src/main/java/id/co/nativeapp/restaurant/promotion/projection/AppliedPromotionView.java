package id.co.nativeapp.restaurant.promotion.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection over an {@code applied_promotion} row — reserved for future reporting
 * (receipt rendering, promo-performance dashboards); not yet wired to a controller in this phase.
 */
public interface AppliedPromotionView {

  UUID getId();

  UUID getOrderId();

  @Nullable UUID getSaleId();

  UUID getRuleId();

  @Nullable UUID getCouponId();

  String getRuleNameSnapshot();

  String getRuleTypeSnapshot();

  @Nullable Long getRateBpSnapshot();

  @Nullable UUID getLineRef();

  long getAmountMinor();

  String getCurrency();
}
