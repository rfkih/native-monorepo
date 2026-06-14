package id.co.nativeapp.employee;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for employee-service (#19) — the HR module's canonical employee, RECORDS ONLY (no
 * payroll/compensation yet). It owns {@code employee} (incl. {@code ptkp_status} and the
 * column-encrypted PII: {@code nik}, {@code bank_account}), {@code employment_contract} (incl.
 * {@code employment_type}, {@code legal_employer_id}, effective-dated), and {@code assignment}
 * (org_unit, {@code reporting_to}, role, effective-dated).
 *
 * <p><strong>Key invariants (ARCHITECTURE.md §2).</strong> Org/team/manager live on the
 * <em>assignment</em>, never the employee; an employee holds multiple <em>concurrent</em>
 * assignments, and all concurrent assignments MUST resolve to the same {@code legal_employer}. The
 * legal employer of each org_unit is learned by CONSUMING {@code OrgUnitCreated}/{@code
 * OrgUnitChanged} into a local org read model; the invariant is enforced at assignment creation
 * against that projection.
 *
 * <p>It is BOTH a producer and a consumer: it PUBLISHES {@code EmployeeChanged} (on employee
 * create/change) and {@code AssignmentChanged} (on assignment create/change) via the transactional
 * outbox (rule 3, Avro, NO PII in the payload), and CONSUMES {@code OrgUnitCreated}/{@code
 * OrgUnitChanged} idempotently into the org read model.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing and the auto-RLS beans/aspect +
 * pinned tx-advisor order) is contributed by {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code ProblemDetail} advice by {@code
 * libs/security} — both Spring Boot auto-configurations this service inherits purely by depending
 * on those libraries, with no copied {@code config} class.
 */
@SpringBootApplication
public class EmployeeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(EmployeeServiceApplication.class, args);
  }
}
