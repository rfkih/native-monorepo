package id.co.nativeapp.finance.ap.domain;

/**
 * Thrown when an AP bill operation is attempted from an incompatible state — e.g. posting an
 * already-posted bill, paying a draft, overpaying, or voiding a paid bill. Mapped to HTTP 409
 * (Conflict) by the AP controllers (RFC-7807).
 */
public class BillStateException extends RuntimeException {

  public BillStateException(String message) {
    super(message);
  }
}
