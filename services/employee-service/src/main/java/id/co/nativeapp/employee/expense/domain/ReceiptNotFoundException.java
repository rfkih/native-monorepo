package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * No receipt is on file for a claim — either the claim genuinely has none yet, or (anti-
 * enumeration, mirroring {@link ClaimNotFoundException}) it is not visible to the caller. Mapped to
 * HTTP 404 by {@code EmployeeApiAdvice}.
 */
public class ReceiptNotFoundException extends RuntimeException {

  public ReceiptNotFoundException(UUID claimId) {
    super("No receipt on file for expense claim " + claimId);
  }
}
