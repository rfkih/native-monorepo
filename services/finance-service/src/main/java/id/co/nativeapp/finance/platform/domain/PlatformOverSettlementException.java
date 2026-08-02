package id.co.nativeapp.finance.platform.domain;

/**
 * The guarded decrement found less outstanding receivable than the requested gross — settling more
 * than the channel is owed (wrong channel, double entry, or sales not yet consumed). Mapped to
 * {@code 422} (ADR 0036): the books cannot absorb it; the user must check the channel's
 * outstanding first.
 */
public class PlatformOverSettlementException extends RuntimeException {

  public PlatformOverSettlementException(String channelCode, long grossMinor, String currency) {
    super(
        "channel "
            + channelCode
            + " does not have "
            + grossMinor
            + " "
            + currency
            + " outstanding to settle — check the channel's outstanding balance");
  }
}
