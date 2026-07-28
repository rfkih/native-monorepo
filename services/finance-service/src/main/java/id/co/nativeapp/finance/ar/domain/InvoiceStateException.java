package id.co.nativeapp.finance.ar.domain;

/**
 * Thrown when an AR invoice operation is attempted from an incompatible state — e.g. issuing an
 * already-issued invoice, paying a draft, overpaying, or voiding a paid invoice. Mapped to HTTP 409
 * (Conflict) by the AR controllers (RFC-7807).
 */
public class InvoiceStateException extends RuntimeException {

  public InvoiceStateException(String message) {
    super(message);
  }
}
