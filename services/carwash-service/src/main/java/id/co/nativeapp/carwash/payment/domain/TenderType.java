package id.co.nativeapp.carwash.payment.domain;

/**
 * How a {@link CarwashPayment} was tendered (ported verbatim from restaurant-service's {@code
 * payment} feature).
 *
 * <p>{@link #CASH} settles synchronously through the live {@code CashProvider}. {@link #QRIS} and
 * {@link #CARD} go through the flagged-pending {@code DigitalProvider} (ADR 0006): the persistence
 * and capture flow are real, but no money actually moves until a real payment-service-provider
 * adapter lands (ADR 0007). Stored as {@code EnumType.STRING} so the {@code
 * carwash_payment.tender_type} column is human-readable and stable against reordering.
 */
public enum TenderType {
  /** Physical cash — settles instantly, carries a tendered amount and change. */
  CASH,

  /** QRIS (Quick Response Code Indonesian Standard) — digital, flagged-pending. */
  QRIS,

  /** Card (debit/credit) — digital, flagged-pending. */
  CARD;

  /** {@code true} for a digital tender that runs through the flagged-pending provider. */
  public boolean isDigital() {
    return this != CASH;
  }
}
