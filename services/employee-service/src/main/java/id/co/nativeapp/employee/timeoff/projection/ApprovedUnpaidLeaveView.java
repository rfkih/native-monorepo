package id.co.nativeapp.employee.timeoff.projection;

import java.util.UUID;

/**
 * One APPROVED {@code UNPAID} leave request whose {@code start_date} falls in the payroll run's
 * period (Track P Phase P7). Since a request is now constrained to a single calendar month at
 * creation (P7 review W2), {@code days} is directly attributable to the run's period — no
 * date-range clipping is needed.
 */
public interface ApprovedUnpaidLeaveView {

  UUID getId();

  UUID getEmployeeId();

  int getDays();
}
