package id.co.nativeapp.employee.operator.dto;

import java.util.UUID;

/**
 * One entry of {@code GET /api/v1/operators/roster} (ADR 0049 P3b/P2) — exactly what the till's PIN
 * picker needs to render a name tile, nothing more (rule 6): no role, no status, no NIK/bank
 * account/user id.
 *
 * @param employeeId the employee to tap
 * @param displayName the employee's full name
 * @param hasPin whether this employee already has an operator PIN set. {@code false} tells the till
 *     to route the tap to {@code POST /api/v1/operators/pin} (first-time enrollment) instead of the
 *     PIN pad. At a no-PIN outlet this is still populated but irrelevant to that flow.
 */
public record OperatorRosterEntry(UUID employeeId, String displayName, boolean hasPin) {}
