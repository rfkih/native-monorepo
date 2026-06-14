package id.co.nativeapp.employee.assignment;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An {@code assignment} — an employee's placement in an {@code org_unit}, with a reporting line and
 * a role, over an effective period. Org/team/manager live HERE, not on the {@link
 * id.co.nativeapp.employee.employee.Employee employee} (ARCHITECTURE.md §2): an employee holds
 * multiple <em>concurrent</em> assignments, and all concurrent assignments MUST resolve to the same
 * {@code legal_employer} (enforced in {@link AssignmentWriter} against the local org read model).
 *
 * <p><strong>Effective-dated.</strong> An open-ended assignment uses the far-future sentinel {@link
 * #OPEN_ENDED} ({@code 9999-12-31}) for {@code effective_to}, never {@code NULL}
 * (ENGINEERING-STANDARDS §2.5). Two assignments are <em>concurrent</em> when their {@code
 * [effective_from, effective_to]} ranges overlap (see {@link #overlaps(LocalDate, LocalDate)}).
 *
 * <p>It extends {@link Auditable}, inheriting the audit + tenancy columns and the {@code
 * assignment} RLS policy (rule 4 + rule 5). It holds no PII.
 */
@Entity
@Table(name = "assignment")
public class Assignment extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never {@code NULL}). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "org_unit_id", nullable = false)
  private UUID orgUnitId;

  @Column(name = "reporting_to")
  private UUID reportingTo;

  @Column(name = "role", nullable = false, length = 128)
  private String role;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected Assignment() {
    // for JPA
  }

  /**
   * Creates an assignment with a freshly generated id, open-ended unless an explicit {@code
   * effectiveTo} is supplied.
   *
   * @param employeeId the employee being assigned; must be non-null
   * @param orgUnitId the org unit assigned to; must be non-null
   * @param reportingTo the manager (employee id) this assignment reports to, or {@code null}
   * @param role the role held; must be non-blank
   * @param effectiveFrom the date the assignment becomes effective; must be non-null
   * @param effectiveTo the date it ends, or {@code null} for open-ended (uses {@link #OPEN_ENDED})
   * @throws IllegalArgumentException if {@code effectiveTo} is before {@code effectiveFrom}
   */
  public Assignment(
      UUID employeeId,
      UUID orgUnitId,
      UUID reportingTo,
      String role,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.reportingTo = reportingTo;
    this.role = requireNonBlank(role, "role");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo == null ? OPEN_ENDED : effectiveTo;
    if (this.effectiveTo.isBefore(this.effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
    }
  }

  /**
   * Whether this assignment's effective period overlaps the given period — i.e. the two are
   * <em>concurrent</em>. Ranges are treated as inclusive on both ends (an assignment ending on the
   * same day another begins is considered to overlap, the conservative choice for the
   * same-legal-employer guard).
   */
  public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
    Objects.requireNonNull(otherFrom, "otherFrom");
    Objects.requireNonNull(otherTo, "otherTo");
    return !this.effectiveFrom.isAfter(otherTo) && !otherFrom.isAfter(this.effectiveTo);
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public UUID getReportingTo() {
    return reportingTo;
  }

  public String getRole() {
    return role;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
}
