package id.co.nativeapp.barbershop.promotion.projection;

import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection over an {@code applied_promotion} row — reserved for future
 * reporting (receipt rendering, promo-performance dashboards); not yet wired to a controller in
 * this phase. Ported from restaurant-service via carwash-service, adapted to {@code ticket_id} in
 * place of {@code order_id}.
 */
public interface AppliedPromotionView {

  UUID getId();

  UUID getTicketId();

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
