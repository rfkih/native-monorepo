package id.co.nativeapp.employee.expense.projection;

import java.util.UUID;

/**
 * One (employee, claim) pair among the claims currently linked to a payroll run AND STILL {@code
 * APPROVED} (P7 review W3) — {@code PayrollRunWriter} freezes these ids into {@code
 * work_inputs_json}'s {@code reimbursement.claimIds} for full reproducibility (previously only the
 * aggregate total/count were frozen, not which claims actually contributed to it).
 */
public interface LinkedClaimIdView {

  UUID getEmployeeId();

  UUID getClaimId();

  /**
   * The claim's own currency — lets the caller filter to the run's base currency, symmetric with
   * {@code LinkedClaimTotalView}'s currency-grouping (never mix currencies into one total, rule 8).
   */
  String getCurrency();
}
