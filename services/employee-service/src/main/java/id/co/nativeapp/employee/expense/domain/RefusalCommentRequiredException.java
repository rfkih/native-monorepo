package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * A refusal was attempted with no (or a blank) comment. Every other decision on a claim may carry
 * an optional comment, but a refusal MUST explain itself (ADR 0030 §5) — the employee needs a
 * reason to fix and resubmit. Mapped to HTTP 422 (Unprocessable Entity) by {@code
 * EmployeeApiAdvice}, distinct from the generic 400 {@code IllegalArgumentException} mapping so
 * this specific, business-meaningful omission is not confused with a malformed request.
 */
public class RefusalCommentRequiredException extends RuntimeException {

  public RefusalCommentRequiredException(UUID claimId) {
    super("Refusing expense claim " + claimId + " requires a comment");
  }
}
