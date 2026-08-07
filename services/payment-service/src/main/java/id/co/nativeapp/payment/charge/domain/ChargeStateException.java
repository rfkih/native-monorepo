package id.co.nativeapp.payment.charge.domain;

/** Thrown by the aggregate when a transition is attempted on a terminal charge — → 409. */
public class ChargeStateException extends RuntimeException {

  public ChargeStateException(String message) {
    super(message);
  }
}
