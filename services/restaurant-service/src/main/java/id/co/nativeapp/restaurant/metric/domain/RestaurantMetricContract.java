package id.co.nativeapp.restaurant.metric.domain;

/**
 * restaurant-service's declared METRIC CONTRACT (ARCHITECTURE.md §2 — each vertical declares which
 * {@code metric_key}s it emits at which grains). Restaurant emits, at the EMPLOYEE grain:
 *
 * <ul>
 *   <li>{@code sales_amount} — the net amount of a sale (minor units); the subject is the CASHIER
 *       who rang it (their Keycloak sub, from {@code sale.created_by} = the bound actor). This
 *       feeds the own-sales commission ({@code PERCENT_OF_METRIC}) in employee-service.
 * </ul>
 *
 * <p>Emitted only when the acting principal is a real user id (a UUID sub) — in the header-trust
 * dev recipe the actor is a fixed non-UUID string, so no metric is emitted (documented dev caveat).
 */
public final class RestaurantMetricContract {

  /** The {@code sales_amount} metric key: net sale amount (minor units) at the employee grain. */
  public static final String SALES_AMOUNT = "sales_amount";

  /** The employee grain — the subject is the cashier (a Keycloak sub). */
  public static final String EMPLOYEE_GRAIN = "employee";

  private RestaurantMetricContract() {
    // static holder
  }
}
