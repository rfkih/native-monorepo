package id.co.nativeapp.restaurant.payment.domain;

/**
 * How a {@link Payment} was tendered.
 *
 * <p>{@link #CASH} settles synchronously through the live {@code CashProvider}. {@link #QRIS} and
 * {@link #CARD} go through the flagged-pending {@code DigitalProvider} (ADR 0006): the persistence,
 * capture flow, GL routing, and events are real, but no money actually moves until a real
 * payment-service-provider adapter lands (ADR 0007). {@link #ONLINE} (ADR 0036 Phase B) is a
 * platform-collected order (GoFood/GrabFood style): the platform already holds the customer's
 * money at acceptance, so it captures SYNCHRONOUSLY like cash (never the pending-digital path) and
 * finance routes its clearing debit to PLATFORM_RECEIVABLE instead of drawer cash. Stored as
 * {@code EnumType.STRING} so the {@code payment.tender_type} column is human-readable and stable
 * against reordering.
 */
public enum TenderType {
  /** Physical cash — settles instantly, carries a tendered amount and change. */
  CASH,

  /** QRIS (Quick Response Code Indonesian Standard) — digital, flagged-pending. */
  QRIS,

  /** Card (debit/credit) — digital, flagged-pending. */
  CARD,

  /**
   * Platform-collected online order (ADR 0036) — synchronous capture (the platform committed the
   * money at acceptance), REQUIRES a sales-channel code, and never carries gift-card or loyalty
   * legs (platform orders settle wholly through the platform).
   */
  ONLINE;

  /** {@code true} for a digital tender that runs through the flagged-pending provider. */
  public boolean isDigital() {
    return this == QRIS || this == CARD;
  }
}
