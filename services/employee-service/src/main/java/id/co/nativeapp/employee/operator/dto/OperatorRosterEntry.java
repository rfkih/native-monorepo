package id.co.nativeapp.employee.operator.dto;

import java.util.UUID;

/**
 * One entry of {@code GET /api/v1/operators/roster} (ADR 0049 P3b) — exactly what the till's PIN
 * picker needs to render a name tile, nothing more (rule 6): no role, no status, no NIK/bank
 * account/user id.
 *
 * @param employeeId the employee to tap
 * @param displayName the employee's full name
 */
public record OperatorRosterEntry(UUID employeeId, String displayName) {}
