package id.co.nativeapp.payment.charge.domain;

/**
 * Thrown when the PSP cannot be reached or answers outside its contract (timeout, 5xx, malformed
 * body) — → 502. The message NEVER carries credentials or raw response bodies (rule 6); the till
 * falls back to another tender / manual QRIS.
 */
public class GatewayUnavailableException extends RuntimeException {

  public GatewayUnavailableException(String message) {
    super(message);
  }

  public GatewayUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
