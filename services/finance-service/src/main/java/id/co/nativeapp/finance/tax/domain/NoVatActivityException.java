package id.co.nativeapp.finance.tax.domain;

/**
 * Thrown when a PPN return is filed for a period that has no VAT to report — no output VAT and no
 * input VAT accrued in the period's GL (there is no balanced netting entry to post), or a
 * pathological net-void period where the period's output/input VAT is negative (voids exceeded
 * issues), which this slice does not support (ADR 0017 deferral). Mapped to {@code 400}.
 */
public class NoVatActivityException extends RuntimeException {

  public NoVatActivityException(String message) {
    super(message);
  }
}
