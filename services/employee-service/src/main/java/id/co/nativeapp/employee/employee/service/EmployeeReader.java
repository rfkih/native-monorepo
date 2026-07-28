package id.co.nativeapp.employee.employee.service;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.dto.ContractResponse;
import id.co.nativeapp.employee.employee.dto.EmployeeListRowResponse;
import id.co.nativeapp.employee.employee.dto.EmployeeLoginResponse;
import id.co.nativeapp.employee.employee.dto.EmployeeResponse;
import id.co.nativeapp.employee.employee.dto.EmployeeWithAssignmentsResponse;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.employee.repository.EmploymentContractRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads an employee together with its assignments — the query side. A
 * {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage: the
 * lookups carry NO {@code WHERE company_id}; the result is constrained solely by the auto-applied
 * RLS policy (rule 5), so a caller only ever sees their own tenant's employee/assignments.
 *
 * <p>The response masks PII (the employee's NIK / bank account; rule 6) — the reader returns the
 * masked {@link EmployeeWithAssignmentsResponse}, never the raw plaintext.
 */
@Service
public class EmployeeReader {

  /** The maximum {@code orgUnitIds} filter size — one IN chunk, well under the ≤1000 rule. */
  static final int MAX_ORG_UNIT_IDS = 200;

  private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "INACTIVE");

  /**
   * The held one-time login password is hidden after this long (ADR 0014 backstop) — covers an
   * employee who changed their password out of band and never opened the app to trigger the purge.
   */
  private static final Duration TEMP_PASSWORD_TTL = Duration.ofDays(14);

  /** Dummy id bound when the caller passed no scope — Spring Data cannot expand an empty list. */
  private static final List<UUID> NO_SCOPE = List.of(new UUID(0L, 0L));

  private final EmployeeRepository employeeRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmploymentContractRepository contractRepository;
  private final Clock clock;

  public EmployeeReader(
      EmployeeRepository employeeRepository,
      AssignmentRepository assignmentRepository,
      EmploymentContractRepository contractRepository,
      Clock clock) {
    this.employeeRepository = employeeRepository;
    this.assignmentRepository = assignmentRepository;
    this.contractRepository = contractRepository;
    this.clock = clock;
  }

  /**
   * The console HR list — one row per (employee × current assignment in scope); see {@link
   * EmployeeRepository#findListRows}. No PII on this path (rule 6).
   *
   * @param orgUnitIds restrict to employees currently assigned in ANY of these org units; null or
   *     empty = the whole tenant (unassigned employees included, with null assignment fields)
   * @param status optional ACTIVE | INACTIVE filter
   * @param q optional case-insensitive name substring
   * @param asOf the effective date for "current" (null = today)
   * @throws IllegalArgumentException on an unknown status or an oversized scope (→ 400)
   */
  @Transactional(readOnly = true)
  public List<EmployeeListRowResponse> list(
      List<UUID> orgUnitIds, String status, String q, LocalDate asOf) {
    if (status != null && !ALLOWED_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "Unsupported status filter: " + status + " (expected ACTIVE or INACTIVE)");
    }
    boolean hasUnits = orgUnitIds != null && !orgUnitIds.isEmpty();
    if (hasUnits && orgUnitIds.size() > MAX_ORG_UNIT_IDS) {
      throw new IllegalArgumentException("orgUnitIds accepts at most " + MAX_ORG_UNIT_IDS + " ids");
    }
    // Escape ILIKE wildcards so a user-typed % or _ matches literally (bound param — no
    // injection either way; this is purely match semantics).
    String query =
        (q == null || q.isBlank())
            ? null
            : q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    LocalDate effective = asOf == null ? LocalDate.now(clock) : asOf;
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return employeeRepository
        .findListRows(hasUnits ? orgUnitIds : NO_SCOPE, hasUnits, status, query, effective)
        .stream()
        .map(
            v ->
                new EmployeeListRowResponse(
                    v.getEmployeeId(),
                    v.getFullName(),
                    v.getStatus(),
                    v.getPtkpStatus(),
                    v.getUserId(),
                    v.getAssignmentId(),
                    v.getOrgUnitId(),
                    v.getRole(),
                    v.getReportingTo(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo(),
                    Boolean.TRUE.equals(v.getHasCompensation())))
        .toList();
  }

  /**
   * The raw {@link Employee} aggregate, within the bound tenant — for internal/test reads that need
   * the audit columns (the HTTP boundary always uses {@link #findWithAssignments(UUID)}, which
   * masks PII). A {@code @Transactional} read so the auto-RLS aspect engages: a cross-tenant
   * employee is invisible (empty). PII on the returned entity stays encrypted at rest and is only
   * ever exposed masked by a response DTO (rule 6).
   */
  @Transactional(readOnly = true)
  public Optional<Employee> findEmployee(UUID employeeId) {
    return employeeRepository.findById(employeeId);
  }

  /**
   * The employee's login state for the owner/manager detail surface — the AUTHORIZED decrypt of the
   * held one-time password (ADR 0014). Loads the full aggregate (the ciphertext lives on the
   * entity, so this is not a masked projection) and returns the decrypted temp ONLY if it is still
   * fresh (within {@link #TEMP_PASSWORD_TTL}); otherwise the credential is treated as absent. The
   * caller is owner/manager-gated at the gateway. NEVER logged.
   *
   * @throws EmployeeNotFoundException if no such employee is visible in the bound tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public EmployeeLoginResponse loginDetail(UUID employeeId) {
    Employee employee =
        employeeRepository
            .findById(employeeId)
            .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    String temp = employee.loginTempPasswordIfFresh(clock.instant().minus(TEMP_PASSWORD_TTL));
    return new EmployeeLoginResponse(employee.getUserId(), temp);
  }

  /**
   * The employee (PII masked) plus its assignments and contracts, within the bound tenant. Empty if
   * no such employee is visible (unknown id, or another tenant's — invisible under RLS). The
   * contracts are what a compensation package references — the console needs their ids.
   */
  @Transactional(readOnly = true)
  public Optional<EmployeeWithAssignmentsResponse> findWithAssignments(UUID employeeId) {
    return employeeRepository
        .findById(employeeId)
        .map(
            employee -> {
              List<AssignmentResponse> assignments =
                  assignmentRepository.findByEmployeeId(employeeId).stream()
                      .map(AssignmentResponse::from)
                      .toList();
              List<ContractResponse> contracts =
                  contractRepository.findByEmployeeId(employeeId).stream()
                      .map(ContractResponse::from)
                      .toList();
              return new EmployeeWithAssignmentsResponse(
                  EmployeeResponse.from(employee), assignments, contracts);
            });
  }
}
