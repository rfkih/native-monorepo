package id.co.nativeapp.employee.timeoff.projection;

import java.util.UUID;

/**
 * One row of the manager-facing per-employee derived leave-balance list ({@code
 * LeaveBalanceRepository#findRowsForYear}) — {@code grantedDays}/{@code adjustmentDays} come from
 * the tenant's {@code leave_balance} row (defaulted in SQL when absent — {@code
 * LeaveBalance#DEFAULT_GRANTED_DAYS}/0); {@code usedDays} is a correlated-subquery SUM of APPROVED
 * ANNUAL {@code leave_request.days} for the year. {@code remaining} is computed in the service
 * layer, not here (CODE-STRUCTURE §3.3 — a projection carries only what the query selects).
 */
public interface LeaveBalanceRowView {

  UUID getEmployeeId();

  String getEmployeeName();

  int getGrantedDays();

  int getAdjustmentDays();

  int getUsedDays();
}
