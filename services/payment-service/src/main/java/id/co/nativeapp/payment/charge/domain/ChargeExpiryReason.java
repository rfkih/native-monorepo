package id.co.nativeapp.payment.charge.domain;

/**
 * Why a live gateway charge terminated WITHOUT settling — the {@code reason} carried on the {@code
 * PaymentChargeExpired} event (ADR 0045). All three mean the same thing to a consumer ("this
 * PENDING tender will never settle; release it"); the distinction is recorded for audit and
 * error-inbox park messages.
 */
public enum ChargeExpiryReason {
  /** The QR timed out — the lazy past-expiry sweep or a status sync flipped it to EXPIRED. */
  EXPIRED,
  /** The cashier cancelled the attempt (and the PSP confirmed no money moved). */
  CANCELED,
  /** The PSP reported the charge FAILED. */
  FAILED
}
