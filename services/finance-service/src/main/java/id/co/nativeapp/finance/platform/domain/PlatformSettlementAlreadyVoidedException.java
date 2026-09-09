package id.co.nativeapp.finance.platform.domain;

import java.util.UUID;

/**
 * The payout has already been taken back (409). Raised by the GUARDED claim rather than a
 * read-then-write check, so two concurrent voids cannot both hand the balance back.
 */
public class PlatformSettlementAlreadyVoidedException extends RuntimeException {

  public PlatformSettlementAlreadyVoidedException(UUID settlementId) {
    super("platform settlement " + settlementId + " has already been voided");
  }
}
