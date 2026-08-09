package id.co.nativeapp.employee.operator.projection;

import java.util.UUID;

/**
 * Read projection for {@code GET /api/v1/operators/roster} (ADR 0049 P3b/P2) — one row per employee
 * who can sign in as an operator at the queried outlet: actively assigned there (today) AND
 * login-linked. {@link #getHasPin()} tells the till whether this employee still needs to enroll a
 * first PIN ({@code POST /api/v1/operators/pin}) before they can complete a PIN-required sign-in —
 * it does NOT gate list membership (an unenrolled, assigned+linked employee is still listed, so the
 * till can offer the enrollment flow for them).
 *
 * <p>Deliberately selects <strong>no PII</strong> (rule 6): only the employee id, full name, and
 * the has-a-PIN flag — exactly what the till's PIN picker shows, nothing an owner/manager role
 * would need (no status, NIK, bank account, or user id). Lives in the feature's {@code projection}
 * package (CODE-STRUCTURE §3.3); snake_case native-query aliases map to these accessors via Spring
 * Data's projection-interface convention.
 */
public interface OperatorRosterView {

  UUID getEmployeeId();

  String getFullName();

  Boolean getHasPin();
}
