package id.co.nativeapp.barbershop.catalog.domain;

/**
 * Thrown when a caller whose role set does not include {@code owner} or {@code manager} attempts to
 * create or modify a {@code staff_profile} (→ {@code 403}, mirroring carwash-service review W3).
 *
 * <p>Staff profiles are commission-routing data: the {@code employee_id} link decides WHICH
 * employee the {@code sales_amount} metric — and therefore {@code PERCENT_OF_METRIC} commission —
 * attributes to. Unlike service/addon prices (which stay cashier-editable at restaurant-menu
 * parity, and which a checkout re-prices server-side anyway), a relinked profile silently redirects
 * money, so profile writes are held to the owner/manager bar.
 */
public class StaffProfileWriteForbiddenException extends RuntimeException {

  public StaffProfileWriteForbiddenException() {
    super("staff profile writes require the owner or manager role");
  }
}
