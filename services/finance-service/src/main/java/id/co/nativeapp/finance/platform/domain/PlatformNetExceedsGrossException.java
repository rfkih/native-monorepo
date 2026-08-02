package id.co.nativeapp.finance.platform.domain;

/**
 * {@code net > gross} — the payout exceeds what was settled, which would make the commission
 * negative. Platform subsidies/top-ups are DEFERRED in v1 (ADR 0036): mapped to {@code 422}.
 */
public class PlatformNetExceedsGrossException extends RuntimeException {

  public PlatformNetExceedsGrossException(long grossMinor, long netMinor) {
    super(
        "net ("
            + netMinor
            + ") exceeds gross ("
            + grossMinor
            + ") — negative commission (platform subsidy) is not supported in v1");
  }
}
