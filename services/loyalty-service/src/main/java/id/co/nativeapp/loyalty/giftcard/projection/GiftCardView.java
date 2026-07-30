package id.co.nativeapp.loyalty.giftcard.projection;

import java.util.UUID;

/**
 * Native-query read projection over a {@code gift_card} row — no PII, so (unlike {@code
 * loyalty_member}) gift-card reads DO use the standard projection convention (CODE-STRUCTURE.md
 * §3.3), never {@code SELECT *} of the entity.
 */
public interface GiftCardView {

  UUID getId();

  String getCode();

  String getState();

  long getBalanceMinor();

  String getCurrency();
}
