package id.co.nativeapp.finance.platform.domain;

import java.util.UUID;

/** No such payout for this tenant — RLS-scoped, so another tenant's id reads as absent (404). */
public class PlatformSettlementNotFoundException extends RuntimeException {

  public PlatformSettlementNotFoundException(UUID settlementId) {
    super("no platform settlement " + settlementId);
  }
}
