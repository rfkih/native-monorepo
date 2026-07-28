package id.co.nativeapp.finance.ar.domain;

/**
 * The lifecycle state of an AR {@link Invoice}.
 *
 * <ul>
 *   <li>{@link #DRAFT} — created, editable, no GL posting yet, no number assigned.
 *   <li>{@link #ISSUED} — posted to the GL (Dr AR / Cr revenue (+ VAT)); number + dates assigned;
 *       awaiting payment.
 *   <li>{@link #PARTIALLY_PAID} — at least one payment received, balance still outstanding.
 *   <li>{@link #PAID} — fully settled (paid == total).
 *   <li>{@link #VOID} — cancelled; an ISSUED invoice is voided by a balanced contra GL entry.
 * </ul>
 */
public enum InvoiceStatus {
  DRAFT,
  ISSUED,
  PARTIALLY_PAID,
  PAID,
  VOID
}
