package id.co.nativeapp.finance.tax.domain;

/**
 * Thrown on an illegal {@link TaxFiling} lifecycle transition — settling an already-SETTLED return,
 * or settling a CREDITABLE / zero-net return (which has nothing to pay). Mapped to {@code 409}
 * (mirrors {@code BillStateException}).
 */
public class TaxFilingStateException extends RuntimeException {

  public TaxFilingStateException(String message) {
    super(message);
  }
}
