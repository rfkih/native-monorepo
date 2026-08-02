package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for {@code POST /{id}/reject} — shared by leave requests and overtime entries. {@code
 * note} is REQUIRED (enforced here at the HTTP boundary; the aggregate re-checks as defense in
 * depth via {@code DecisionCommentRequiredException}).
 */
public record RejectTimeoffRequest(@NotBlank @Size(max = 4000) String note) {}
