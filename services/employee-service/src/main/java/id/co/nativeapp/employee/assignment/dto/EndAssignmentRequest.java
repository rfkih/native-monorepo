package id.co.nativeapp.employee.assignment.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * The end-assignment request body ({@code PATCH /api/v1/employees/{eid}/assignments/{aid}}) — an
 * effective-dated close, never a delete.
 *
 * @param endOn the assignment's last effective day; must be on or after its {@code effectiveFrom}
 */
public record EndAssignmentRequest(@NotNull LocalDate endOn) {}
