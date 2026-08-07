package id.co.nativeapp.payment.charge.domain;

/**
 * Thrown when an inbound PSP webhook fails authentication — a malformed body, an unknown/forged
 * tenant (RLS-invisible settings), or a bad signature. Mapped to a UNIFORM 401 with no detail (→
 * {@code PaymentAdvice}): the response must never disclose WHICH check failed (no oracle).
 */
public class WebhookRejectedException extends RuntimeException {

  public WebhookRejectedException() {
    super("Webhook rejected.");
  }
}
