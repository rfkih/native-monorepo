package id.co.nativeapp.employee.assignment.controller;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentRequest;
import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.assignment.dto.EndAssignmentRequest;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/employees/{employeeId}/assignments} — add an assignment to an employee, enforcing
 * the same-legal-employer invariant (ARCHITECTURE.md §2).
 *
 * <ul>
 *   <li>{@code POST .../assignments} — create an assignment; returns {@code 201} + {@code
 *       Location}, emits {@code AssignmentChanged}. A concurrent assignment under a DIFFERENT legal
 *       employer is rejected with {@code 409} (via {@link
 *       id.co.nativeapp.employee.config.EmployeeApiAdvice}); an org unit unknown to the local read
 *       model is rejected with {@code 400}.
 *   <li>{@code PATCH .../assignments/{assignmentId}} — end an OPEN assignment on a date
 *       (effective-dated close, never a delete); returns {@code 200}, emits a second {@code
 *       AssignmentChanged} for the same aggregate. {@code 409} when not open, {@code 404} when the
 *       assignment is not visible for that employee.
 * </ul>
 *
 * <p>A thin HTTP adapter: maps the {@code AddAssignmentRequest} (+ the path {@code employeeId}) to
 * an {@code AddAssignmentCommand}, calls exactly one service method, and maps the result to a DTO.
 * The tenant is bound at the edge, so RLS scopes the org-read-model lookup and the assignment
 * write, and {@code company_id} is stamped from that scope, never the body (rule 5). No PII on this
 * resource.
 */
@Tag(
    name = "Assignments",
    description =
        "Add effective-dated assignments to an employee, enforcing the same-legal-employer"
            + " invariant")
@RestController
@RequestMapping("/api/v1/employees/{employeeId}/assignments")
public class AssignmentController {

  private final AssignmentService assignmentService;

  public AssignmentController(AssignmentService assignmentService) {
    this.assignmentService = assignmentService;
  }

  @Operation(
      summary = "Add an assignment to an employee",
      description =
          "Creates an effective-dated assignment under the bound tenant, enforcing the"
              + " same-legal-employer invariant (a concurrent assignment under a different legal"
              + " employer is rejected with 409); an org unit unknown to the local read model is"
              + " rejected with 400. Returns 201 + Location and emits AssignmentChanged.")
  @PostMapping
  public ResponseEntity<AssignmentResponse> addAssignment(
      @PathVariable UUID employeeId, @Valid @RequestBody AddAssignmentRequest request) {
    AddAssignmentCommand command =
        new AddAssignmentCommand(
            employeeId,
            request.orgUnitId(),
            request.reportingTo(),
            request.role(),
            request.effectiveFrom(),
            request.effectiveTo());
    Assignment created = assignmentService.add(command);
    AssignmentResponse body = AssignmentResponse.from(created);
    return ResponseEntity.created(
            URI.create("/api/v1/employees/" + employeeId + "/assignments/" + body.id()))
        .body(body);
  }

  @Operation(
      summary = "End an open assignment",
      description =
          "Sets the assignment's effective_to (an effective-dated close — never a delete) and"
              + " emits a second AssignmentChanged for the same aggregate. 409 when the assignment"
              + " is not open; 404 when it is not visible for that employee in the bound tenant.")
  @PatchMapping("/{assignmentId}")
  public ResponseEntity<AssignmentResponse> endAssignment(
      @PathVariable UUID employeeId,
      @PathVariable UUID assignmentId,
      @Valid @RequestBody EndAssignmentRequest request) {
    Assignment ended = assignmentService.end(employeeId, assignmentId, request.endOn());
    return ResponseEntity.ok(AssignmentResponse.from(ended));
  }
}
