package id.co.nativeapp.restaurant.integrity.projection;

/**
 * Read projection for one operator's till activity over the window — the raw counts behind the
 * void, refund and discount rate checks.
 *
 * <p>The operator is {@code COALESCE(sold_by_user_id, created_by)}: on an outlet terminal the
 * verified operator rang the sale while the DEVICE credential owns the audit column, so taking
 * {@code created_by} alone would attribute a whole shift's activity to a kiosk. On an ordinary
 * console login there is no operator session and {@code created_by} is the person — hence the
 * fallback rather than a choice between the two.
 *
 * <p>Counts, never rates. Rates are derived in the service against the REST of the outlet, so an
 * actor can never be compared against a baseline they themselves dominate.
 *
 * <p>Backs {@code SalesIntegrityRepository.findOperatorActivity}.
 */
public interface OperatorActivityView {

  /**
   * The operator's Keycloak subject or login id — an identifier, resolved to a name client-side.
   */
  String getActor();

  /** Payments this operator took in the window, whatever their eventual status. */
  long getPaymentCount();

  long getVoidCount();

  long getVoidMinor();

  /** Σ order-level discount they applied, in minor units. */
  long getDiscountMinor();

  /** Σ payment amount they took, the denominator the discount rate is measured against. */
  long getGrossMinor();

  /** Payments they took settled in CASH — the numerator of the tender-mix check. */
  long getCashCount();

  String getCurrency();
}
