package id.co.nativeapp.payment.charge.domain;

/** Thrown when a charge id is unknown or cross-tenant (RLS-invisible) — → 404. */
public class ChargeNotFoundException extends RuntimeException {

  public ChargeNotFoundException() {
    super("No such payment charge is accessible.");
  }
}
