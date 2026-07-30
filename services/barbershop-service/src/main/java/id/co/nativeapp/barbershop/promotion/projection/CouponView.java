package id.co.nativeapp.barbershop.promotion.projection;

import java.time.Instant;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Native-query read projection over a {@code coupon} row for the admin listing endpoint (no rule
 * join — see {@link CouponRuleView} for the engine's joined lookup). Ported verbatim from
 * restaurant-service via carwash-service.
 */
public interface CouponView {

  UUID getId();

  String getCode();

  UUID getRuleId();

  int getMaxRedemptions();

  int getRedeemedCount();

  @Nullable Instant getExpiresAt();

  boolean isActive();
}
