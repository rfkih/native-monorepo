package id.co.nativeapp.finance.ap.domain;

/**
 * The lifecycle state of an AP {@link Bill}.
 *
 * <ul>
 *   <li>{@link #DRAFT} — created, editable, no GL posting yet, no number assigned.
 *   <li>{@link #POSTED} — posted to the GL (Dr expense (+ VAT input) / Cr AP); number + dates
 *       assigned; awaiting payment.
 *   <li>{@link #PARTIALLY_PAID} — at least one payment made, balance still outstanding.
 *   <li>{@link #PAID} — fully settled (paid == total).
 *   <li>{@link #VOID} — cancelled; a POSTED bill is voided by a balanced contra GL entry.
 * </ul>
 */
public enum BillStatus {
  DRAFT,
  POSTED,
  PARTIALLY_PAID,
  PAID,
  VOID
}
