package id.co.nativeapp.barbershop.catalog.dto;

import java.util.UUID;

/**
 * Partial-update request body for a {@code staff_profile} row. Every field is OPTIONAL — a {@code
 * null} field leaves that attribute unchanged. {@link #employeeId()} cannot be distinguished from
 * "unlink" via this shape (a {@code null} always means "leave unchanged"); unlinking is a rare
 * operation deferred to a future explicit endpoint if needed.
 *
 * @param displayLabel the new label, or {@code null} to leave unchanged
 * @param employeeId the new employee link, or {@code null} to leave unchanged
 * @param active the new active flag, or {@code null} to leave unchanged
 */
public record StaffProfilePatchRequest(String displayLabel, UUID employeeId, Boolean active) {}
