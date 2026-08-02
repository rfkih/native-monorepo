package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * A void was attempted with no (or a blank) comment. Voiding contra's money already recognised on
 * the books (ADR 0030 §5) — like a refusal, the void MUST explain itself. Mapped nowhere: exactly
 * like {@link RefusalCommentRequiredException}, this is DEFENSE-IN-DEPTH ONLY — {@code
 * VoidClaimRequest.comment}'s {@code @NotBlank} already rejects a blank comment with HTTP 400
 * before {@link ExpenseClaim#voidClaim} is ever reached, so no {@code @ExceptionHandler} maps this
 * specifically; it exists purely so a future non-HTTP caller cannot slip a blank comment past the
 * aggregate's own invariant.
 */
public class VoidCommentRequiredException extends RuntimeException {

  public VoidCommentRequiredException(UUID claimId) {
    super("Voiding expense claim " + claimId + " requires a comment");
  }
}
