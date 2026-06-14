package id.co.nativeapp.employee.employee;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An {@code employment_contract} — the legal employment relationship between an {@link Employee}
 * and a {@code legal_employer}, of a given {@link EmploymentType}, over an effective period.
 *
 * <p>The employee's legal employer(s) are the distinct {@code legal_employer_id}s across their
 * contracts; the same-legal-employer assignment invariant (ARCHITECTURE.md §2) resolves each
 * assignment's org-unit legal employer (from the local org read model) and rejects a concurrent
 * assignment under a different one.
 *
 * <p><strong>Effective-dated.</strong> An open-ended contract uses the far-future sentinel {@link
 * #OPEN_ENDED} ({@code 9999-12-31}) for {@code effective_to}, never {@code NULL}
 * (ENGINEERING-STANDARDS §2.5).
 *
 * <p>It extends {@link Auditable}, inheriting the audit + tenancy columns and the {@code
 * employment_contract} RLS policy (rule 4 + rule 5). It holds no PII and no Money (this is the
 * records-only slice; compensation is deferred).
 */
@Entity
@Table(name = "employment_contract")
public class EmploymentContract extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never {@code NULL}). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "employment_type", nullable = false, length = 32)
  private EmploymentType employmentType;

  @Column(name = "legal_employer_id", nullable = false, updatable = false)
  private UUID legalEmployerId;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected EmploymentContract() {
    // for JPA
  }

  /**
   * Creates an employment contract with a freshly generated id, open-ended unless an explicit
   * {@code effectiveTo} is supplied.
   *
   * @param employeeId the employee this contract is for; must be non-null
   * @param employmentType the contract kind; must be non-null
   * @param legalEmployerId the legal employer; must be non-null
   * @param effectiveFrom the date the contract becomes effective; must be non-null
   * @param effectiveTo the date the contract ends, or {@code null} for open-ended (uses {@link
   *     #OPEN_ENDED})
   * @throws IllegalArgumentException if {@code effectiveTo} is before {@code effectiveFrom}
   */
  public EmploymentContract(
      UUID employeeId,
      EmploymentType employmentType,
      UUID legalEmployerId,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.employmentType = Objects.requireNonNull(employmentType, "employmentType");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo == null ? OPEN_ENDED : effectiveTo;
    if (this.effectiveTo.isBefore(this.effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public EmploymentType getEmploymentType() {
    return employmentType;
  }

  public UUID getLegalEmployerId() {
    return legalEmployerId;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
}
