package id.co.nativeapp.employee.payroll.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code comp_package} aggregate (design §1): one effective-dated compensation package per
 * (employee, employment contract). Its {@code base_pay} is <strong>PII</strong> (rule 6 names
 * salary explicitly) and is therefore <strong>column-encrypted at rest</strong> via {@link
 * MoneyPiiConverter} into a ciphertext {@code TEXT} column {@code base_pay_enc} — never a plaintext
 * {@code amount_minor}/{@code currency} pair. The domain works in {@link Money}; the row holds only
 * AES-256-GCM ciphertext.
 *
 * <p>Effective-dated with the {@code 9999-12-31} open-ended sentinel (never NULL). Extends {@link
 * Auditable} (rule 4); under the {@code comp_package} RLS policy (rule 5). {@link #toString()}
 * omits the encrypted base pay (PII never logged).
 */
@Entity
@Table(name = "comp_package")
public class CompensationPackage extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  @Column(name = "employment_contract_id", nullable = false, updatable = false)
  private UUID employmentContractId;

  /**
   * Base pay — PII. Column-encrypted at rest as ciphertext {@code TEXT} ({@code base_pay_enc}) via
   * {@link MoneyPiiConverter}; the {@link Money} value lives only in memory and is never logged,
   * evented, or returned unmasked.
   */
  @Convert(converter = MoneyPiiConverter.class)
  @Column(name = "base_pay_enc", nullable = false, columnDefinition = "text")
  private Money basePay;

  @Enumerated(EnumType.STRING)
  @Column(name = "pay_frequency", nullable = false, length = 16)
  private PayFrequency payFrequency;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected CompensationPackage() {
    // for JPA
  }

  /**
   * Creates a compensation package with a freshly generated id, open-ended unless {@code
   * effectiveTo} is supplied.
   */
  public CompensationPackage(
      UUID employeeId,
      UUID employmentContractId,
      Money basePay,
      PayFrequency payFrequency,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.employmentContractId =
        Objects.requireNonNull(employmentContractId, "employmentContractId");
    this.basePay = Objects.requireNonNull(basePay, "basePay");
    this.payFrequency = Objects.requireNonNull(payFrequency, "payFrequency");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo == null ? OPEN_ENDED : effectiveTo;
    if (this.effectiveTo.isBefore(this.effectiveFrom)) {
      throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
    }
  }

  /** Whether this package's effective period covers the given as-of date (inclusive). */
  public boolean coversAsOf(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    return !asOf.isBefore(effectiveFrom) && !asOf.isAfter(effectiveTo);
  }

  /**
   * Whether this package's effective period overlaps the given period (inclusive both ends) — the
   * double-pay guard: the payroll run SUMS every covering package, so two overlapping packages
   * would both contribute.
   */
  public boolean overlaps(LocalDate otherFrom, LocalDate otherTo) {
    Objects.requireNonNull(otherFrom, "otherFrom");
    Objects.requireNonNull(otherTo, "otherTo");
    return !this.effectiveFrom.isAfter(otherTo) && !otherFrom.isAfter(this.effectiveTo);
  }

  /**
   * Ends this package on the given date (effective-dated close — never a delete). Only an OPEN
   * package (still at the {@link #OPEN_ENDED} sentinel) can be ended.
   *
   * @param endOn the last effective day; must be on or after {@code effectiveFrom}
   * @throws CompensationAlreadyEndedException if the package is not open (→ 409)
   * @throws IllegalArgumentException if {@code endOn} is before {@code effectiveFrom} (→ 400)
   */
  public void end(LocalDate endOn) {
    Objects.requireNonNull(endOn, "endOn");
    if (!OPEN_ENDED.equals(this.effectiveTo)) {
      throw new CompensationAlreadyEndedException(this.id);
    }
    if (endOn.isBefore(this.effectiveFrom)) {
      throw new IllegalArgumentException("endOn must not be before effectiveFrom");
    }
    this.effectiveTo = endOn;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public UUID getEmploymentContractId() {
    return employmentContractId;
  }

  /**
   * The decrypted base pay {@link Money}. Restricted to the engine/service; never logged/evented.
   */
  public Money getBasePay() {
    return basePay;
  }

  public PayFrequency getPayFrequency() {
    return payFrequency;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  /** PII-safe: id only, NEVER the base pay (rule 6 — salary never logged). */
  @Override
  public String toString() {
    return "CompensationPackage[id=" + id + ", employeeId=" + employeeId + "]";
  }
}
