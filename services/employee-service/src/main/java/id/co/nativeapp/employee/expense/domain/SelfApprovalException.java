package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * A login attempted to approve their OWN expense claim — forbidden regardless of role, owners
 * included (ADR 0030 §5). Mapped to HTTP 403 (Forbidden) by {@code EmployeeApiAdvice}: unlike a
 * {@code ClaimStateException}, the claim's state is not the problem — the actor is.
 */
public class SelfApprovalException extends RuntimeException {

  public SelfApprovalException(UUID claimId) {
    super("Cannot approve your own expense claim " + claimId);
  }
}
