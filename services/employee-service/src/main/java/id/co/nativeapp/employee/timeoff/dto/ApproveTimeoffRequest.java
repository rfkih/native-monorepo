package id.co.nativeapp.employee.timeoff.dto;

import jakarta.validation.constraints.Size;

/**
 * The body for {@code POST /{id}/approve} — shared by leave requests and overtime entries (the
 * identical guarded-transition shape, ADR 0033 §3). {@code note} is optional.
 */
public record ApproveTimeoffRequest(@Size(max = 4000) String note) {}
