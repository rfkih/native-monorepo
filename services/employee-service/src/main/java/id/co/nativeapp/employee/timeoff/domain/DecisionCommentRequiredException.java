package id.co.nativeapp.employee.timeoff.domain;

import java.util.UUID;

/**
 * A REJECTED decision on a leave request or overtime entry was attempted with no note — the
 * employee needs a reason (the expense-claim refuse-requires-comment idiom, ADR 0030 §5, mirrored
 * here). Mapped to {@code 422} by {@code EmployeeApiAdvice}.
 *
 * <p>Defense-in-depth only: the controller's {@code @NotBlank} on the reject request body already
 * rejects a blank comment with {@code 400} before the aggregate is ever reached over HTTP — this
 * guards a future non-HTTP caller of the writer.
 */
public class DecisionCommentRequiredException extends RuntimeException {

  public DecisionCommentRequiredException(String resource, UUID requestId) {
    super("A rejection comment is required for " + resource + " " + requestId);
  }
}
