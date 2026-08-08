package id.co.nativeapp.employee.operator.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code operator_pin} aggregate (ADR 0049 P1) — a per-employee PIN used to identify a cashier
 * at a shared outlet terminal. {@code POST /api/v1/operators/session} verifies {@code (employeeId,
 * pin)} against this row and, on success, mints a self-contained operator token; owner/manager
 * set/reset the PIN via {@code PUT /api/v1/employees/{id}/operator-pin} — the PIN itself is
 * write-only from this aggregate outward (it never appears in a response, log, event, or {@link
 * #toString()}, rule 6).
 *
 * <p><strong>{@link #pinHash} is a one-way Argon2id PHC string</strong> ({@code
 * operator.config.OperatorPinEncoderConfig}), NOT column-encrypted (the {@code
 * PiiAttributeConverter} is for reversible values; a KDF hash is never decrypted back).
 *
 * <p><strong>Lockout is an aggregate invariant.</strong> {@link #recordFailedAttempt(Instant)}
 * increments {@link #failedAttempts} and, once it reaches {@link #MAX_FAILED_ATTEMPTS}, locks the
 * PIN for {@link #LOCK_DURATION} — a brute-force backstop on top of the gateway's per-tenant rate
 * limit and the employee-pick + PIN design (ADR 0049). {@link #recordSuccessfulVerification()}
 * resets both on a correct PIN.
 *
 * <p>It extends {@link Auditable}, inheriting the audit + tenancy columns and the {@code
 * operator_pin} RLS policy (rule 4 + rule 5). It holds no PII (the hash is a credential, not
 * personal data, and is never decrypted).
 */
@Entity
@Table(name = "operator_pin")
public class OperatorPin extends Auditable {

  /** Consecutive wrong-PIN attempts before the PIN locks (ADR 0049 P1 brute-force backstop). */
  public static final int MAX_FAILED_ATTEMPTS = 5;

  /** How long a locked PIN stays locked once {@link #MAX_FAILED_ATTEMPTS} is reached. */
  public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "employee_id", nullable = false, updatable = false)
  private UUID employeeId;

  /**
   * The Argon2id PHC string. NEVER the plaintext PIN — write-only from the aggregate's public
   * surface outward; {@link #getPinHash()} exists solely for the verify path to feed {@code
   * PasswordEncoder.matches}.
   */
  @Column(name = "pin_hash", nullable = false)
  private String pinHash;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  protected OperatorPin() {
    // for JPA
  }

  /**
   * Creates a fresh operator PIN row (owner/manager set path) — starts unlocked with zero failed
   * attempts.
   *
   * @param employeeId the employee this PIN belongs to; must be non-null
   * @param pinHash the Argon2id PHC string; must be non-blank
   */
  public OperatorPin(UUID employeeId, String pinHash) {
    this.id = UUID.randomUUID();
    this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
    this.pinHash = requireNonBlank(pinHash, "pinHash");
    this.failedAttempts = 0;
    this.lockedUntil = null;
  }

  /**
   * Owner/manager reset: replaces the hash and clears any lockout state — a reset is a fresh start,
   * not a continuation of the previous failure count.
   *
   * @param newPinHash the new Argon2id PHC string; must be non-blank
   */
  public void reset(String newPinHash) {
    this.pinHash = requireNonBlank(newPinHash, "pinHash");
    this.failedAttempts = 0;
    this.lockedUntil = null;
  }

  /** Whether this PIN is currently locked (its {@link #lockedUntil} is still in the future). */
  public boolean isLocked(Instant now) {
    Objects.requireNonNull(now, "now");
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /**
   * Records a failed verification attempt, locking the PIN for {@link #LOCK_DURATION} once {@link
   * #MAX_FAILED_ATTEMPTS} is reached.
   */
  public void recordFailedAttempt(Instant now) {
    Objects.requireNonNull(now, "now");
    this.failedAttempts++;
    if (this.failedAttempts >= MAX_FAILED_ATTEMPTS) {
      this.lockedUntil = now.plus(LOCK_DURATION);
    }
  }

  /** Records a successful verification — clears the failure count and any lockout. */
  public void recordSuccessfulVerification() {
    this.failedAttempts = 0;
    this.lockedUntil = null;
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

  /** The Argon2id PHC string — restricted to the verify path ({@code PasswordEncoder.matches}). */
  public String getPinHash() {
    return pinHash;
  }

  public int getFailedAttempts() {
    return failedAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  /**
   * A credential-safe representation: id / employeeId / lock state only, NEVER the hash (rule 6 — a
   * credential never logged). An accidental {@code log.info("pin={}", operatorPin)} cannot leak
   * anything usable.
   */
  @Override
  public String toString() {
    return "OperatorPin[id="
        + id
        + ", employeeId="
        + employeeId
        + ", failedAttempts="
        + failedAttempts
        + ", locked="
        + (lockedUntil != null)
        + "]";
  }
}
