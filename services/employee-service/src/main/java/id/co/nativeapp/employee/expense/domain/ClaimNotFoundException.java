package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * An expense claim referenced by id is not visible in the bound tenant/owning employee scope — an
 * unknown id, another employee's claim (on the {@code /me} surface), or — invisible under RLS —
 * another tenant's, all collapse to this same {@code 404} (anti-enumeration). Mapped by {@code
 * EmployeeApiAdvice}.
 */
public class ClaimNotFoundException extends RuntimeException {

  public ClaimNotFoundException(UUID claimId) {
    super("Expense claim " + claimId + " not found");
  }
}
