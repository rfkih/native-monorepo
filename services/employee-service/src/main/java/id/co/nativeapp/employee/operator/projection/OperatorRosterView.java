package id.co.nativeapp.employee.operator.projection;

import java.util.UUID;

/**
 * Read projection for {@code GET /api/v1/operators/roster} (ADR 0049 P3b) — one row per employee
 * who can sign in as an operator at the queried outlet: actively assigned there (today) AND
 * carrying an {@code operator_pin} row.
 *
 * <p>Deliberately selects <strong>no PII</strong> (rule 6): only the employee id and full name —
 * exactly what the till's PIN picker shows, nothing an owner/manager role would need (no status,
 * NIK, bank account, or user id). Lives in the feature's {@code projection} package (CODE-STRUCTURE
 * §3.3); snake_case native-query aliases map to these accessors via Spring Data's
 * projection-interface convention.
 */
public interface OperatorRosterView {

  UUID getEmployeeId();

  String getFullName();
}
