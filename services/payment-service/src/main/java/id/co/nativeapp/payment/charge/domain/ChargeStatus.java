package id.co.nativeapp.payment.charge.domain;

/**
 * Lifecycle of a dynamic-QRIS gateway charge (ADR 0045): {@link #INITIATED} (row committed BEFORE
 * the PSP call) → {@link #QR_ISSUED} (Midtrans returned the QR) → exactly one terminal state.
 * {@link #SUCCEEDED} is the only state that emits {@code PaymentChargeSucceeded}; the transition is
 * optimistic-lock-guarded so a webhook/sync race produces one event.
 */
public enum ChargeStatus {
  INITIATED,
  QR_ISSUED,
  SUCCEEDED,
  EXPIRED,
  CANCELED,
  FAILED;

  /** A live charge blocks a second charge for the same payment (the V3 partial unique). */
  public boolean isLive() {
    return this == INITIATED || this == QR_ISSUED;
  }
}
