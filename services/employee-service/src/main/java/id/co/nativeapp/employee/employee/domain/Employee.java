package id.co.nativeapp.employee.employee.domain;

import id.co.nativeapp.employee.config.PiiAttributeConverter;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code employee} aggregate — the HR module's canonical person. It owns the person's identity
 * ({@code full_name}, {@code ptkp_status}), employment {@code status}, and the two PII fields
 * ({@code nik}, {@code bank_account}); it deliberately holds NO org/team/manager — those live on
 * the {@link id.co.nativeapp.employee.assignment.domain.Assignment assignment} (ARCHITECTURE.md
 * §2).
 *
 * <p><strong>PII at rest (rule 6).</strong> {@code nik} and {@code bank_account} are column-level
 * encrypted via {@link PiiAttributeConverter} (AES-256-GCM, random IV per value): the database row
 * only ever holds ciphertext, while the entity works in plaintext. The plaintext NEVER leaves this
 * aggregate as-is to a log, a {@link #toString()}, an event payload, or a DTO — callers read the
 * {@linkplain #maskedNik() masked} / {@linkplain #maskedBankAccount() last-4} forms for responses
 * and only the encrypt/decrypt path ever touches the raw value.
 *
 * <p>It extends {@link Auditable}, inheriting the mandatory audit + tenancy columns and the {@code
 * employee} RLS policy (rule 4 + rule 5).
 */
@Entity
@Table(name = "employee")
public class Employee extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Enumerated(EnumType.STRING)
  @Column(name = "ptkp_status", nullable = false, length = 16)
  private PtkpStatus ptkpStatus;

  /**
   * The Indonesian national id number (NIK) — PII. Column-encrypted at rest via {@link
   * PiiAttributeConverter}; the plaintext is held only in memory and never logged/serialized.
   */
  @Convert(converter = PiiAttributeConverter.class)
  @Column(name = "nik", nullable = false)
  private String nik;

  /** The employee's bank account number — PII. Column-encrypted at rest (see {@link #nik}). */
  @Convert(converter = PiiAttributeConverter.class)
  @Column(name = "bank_account", nullable = false)
  private String bankAccount;

  /**
   * The Indonesian tax id (NPWP) — PII. Column-encrypted at rest exactly like {@link #nik}, but
   * NULLABLE with no backfill (V10): a new employee, or one HR hasn't recorded it for yet, simply
   * has none on file. {@link #hasNpwp()} — not the plaintext — is the payroll engine's signal for
   * the UU PPh Art 21(5a) no-NPWP x120% surcharge.
   */
  @Convert(converter = PiiAttributeConverter.class)
  @Column(name = "npwp_enc")
  private String npwp;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private EmployeeStatus status;

  /**
   * The linked console login — the Keycloak subject id ({@code sub}), or null when the employee has
   * no login. NOT PII (a stable opaque id, the same value org-service stores on outlet
   * assignments); it is the join key for the /me self-service surface and own-sales commission.
   */
  @Column(name = "user_id", length = 64)
  private String userId;

  /**
   * The one-time login password held for the owner to hand to the employee, until the employee
   * first authenticates. A CREDENTIAL — column-encrypted at rest exactly like {@link #nik} (rule
   * 6), never logged, never in the {@link #toString()}, and exposed ONLY via the owner/manager
   * login-detail endpoint (ADR 0014). Null once purged (activation) or when never set.
   */
  @Convert(converter = PiiAttributeConverter.class)
  @Column(name = "login_temp_password_enc")
  private String loginTempPassword;

  /** When {@link #loginTempPassword} was set — drives the TTL backstop that hides a stale value. */
  @Column(name = "login_temp_password_set_at")
  private Instant loginTempPasswordSetAt;

  protected Employee() {
    // for JPA
  }

  /**
   * Creates an active employee with a freshly generated id, validating its construction.
   *
   * @param fullName the person's full name; must be non-blank
   * @param ptkpStatus the PTKP tax status; must be non-null
   * @param nik the national id number (PII); must be non-blank
   * @param bankAccount the bank account number (PII); must be non-blank
   */
  public Employee(String fullName, PtkpStatus ptkpStatus, String nik, String bankAccount) {
    this.id = UUID.randomUUID();
    this.fullName = requireNonBlank(fullName, "fullName");
    this.ptkpStatus = Objects.requireNonNull(ptkpStatus, "ptkpStatus");
    this.nik = requireNonBlank(nik, "nik");
    this.bankAccount = requireNonBlank(bankAccount, "bankAccount");
    this.status = EmployeeStatus.ACTIVE;
  }

  /**
   * Applies an update to the mutable record fields. A null argument leaves the corresponding field
   * unchanged (a partial update); PII fields are replaced only when a new non-blank value is given.
   *
   * @return {@code true} if any field actually changed (so the caller can decide whether to emit an
   *     {@code EmployeeChanged}), {@code false} if the update was a no-op
   */
  public boolean update(
      String newFullName,
      PtkpStatus newPtkpStatus,
      String newNik,
      String newBankAccount,
      String newNpwp,
      EmployeeStatus newStatus) {
    boolean changed = false;
    if (newFullName != null) {
      String trimmed = requireNonBlank(newFullName, "fullName");
      if (!trimmed.equals(this.fullName)) {
        this.fullName = trimmed;
        changed = true;
      }
    }
    if (newPtkpStatus != null && newPtkpStatus != this.ptkpStatus) {
      this.ptkpStatus = newPtkpStatus;
      changed = true;
    }
    if (newNik != null) {
      String trimmed = requireNonBlank(newNik, "nik");
      if (!trimmed.equals(this.nik)) {
        this.nik = trimmed;
        changed = true;
      }
    }
    if (newBankAccount != null) {
      String trimmed = requireNonBlank(newBankAccount, "bankAccount");
      if (!trimmed.equals(this.bankAccount)) {
        this.bankAccount = trimmed;
        changed = true;
      }
    }
    if (newNpwp != null) {
      String trimmed = requireNonBlank(newNpwp, "npwp");
      if (!trimmed.equals(this.npwp)) {
        this.npwp = trimmed;
        changed = true;
      }
    }
    if (newStatus != null && newStatus != this.status) {
      this.status = newStatus;
      changed = true;
    }
    return changed;
  }

  /**
   * Links this employee to a console login (the Keycloak subject id). Idempotent for the same user;
   * relinking to a DIFFERENT user requires an explicit unlink first — a silent overwrite would
   * quietly re-route the /me surface and own-sales commission to another person.
   *
   * @throws IllegalStateException if already linked to a different user (→ 409)
   */
  public void linkUser(String newUserId) {
    String trimmed = requireNonBlank(newUserId, "userId");
    if (this.userId != null && !this.userId.equals(trimmed)) {
      throw new IllegalStateException(
          "Employee " + id + " is already linked to a different login; unlink first");
    }
    this.userId = trimmed;
  }

  /** Removes the console-login link (the login itself lives in Keycloak, untouched). */
  public void unlinkUser() {
    this.userId = null;
    clearLoginTempPassword();
  }

  /**
   * Holds a fresh one-time login password (a credential — encrypted at rest, ADR 0014), stamping
   * the set time for the TTL backstop. Replaces any previous value (a reset supersedes the old
   * one).
   *
   * @param plaintext the temporary password to hold; must be non-blank
   * @param now the set timestamp (the caller supplies the clock)
   */
  public void setLoginTempPassword(String plaintext, Instant now) {
    this.loginTempPassword = requireNonBlank(plaintext, "loginTempPassword");
    this.loginTempPasswordSetAt = Objects.requireNonNull(now, "now");
  }

  /**
   * Purges the held one-time password (activation, unlink, or expiry).
   *
   * @return {@code true} if a value was actually cleared (so the caller can skip a no-op write)
   */
  public boolean clearLoginTempPassword() {
    if (this.loginTempPassword == null && this.loginTempPasswordSetAt == null) {
      return false;
    }
    this.loginTempPassword = null;
    this.loginTempPasswordSetAt = null;
    return true;
  }

  /** Whether a one-time login password is currently held (ignoring freshness). */
  public boolean hasLoginTempPassword() {
    return loginTempPassword != null;
  }

  /**
   * The decrypted one-time login password IF it is still fresh (set at or after {@code cutoff}),
   * else {@code null}. <strong>Restricted:</strong> this returns a credential — it is used ONLY by
   * the owner/manager login-detail path and is never logged, evented, or placed in a general DTO
   * (rule 6, ADR 0014). A value older than the TTL cutoff is treated as absent (the backstop for an
   * employee who changed their password out of band and never opened the app).
   */
  public String loginTempPasswordIfFresh(Instant cutoff) {
    if (loginTempPassword == null || loginTempPasswordSetAt == null) {
      return null;
    }
    return loginTempPasswordSetAt.isBefore(cutoff) ? null : loginTempPassword;
  }

  /** When the held one-time password was set, or null if none is held. */
  public Instant getLoginTempPasswordSetAt() {
    return loginTempPasswordSetAt;
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

  public String getFullName() {
    return fullName;
  }

  public PtkpStatus getPtkpStatus() {
    return ptkpStatus;
  }

  public EmployeeStatus getStatus() {
    return status;
  }

  /** The linked login's Keycloak subject id, or null when the employee has no login. */
  public String getUserId() {
    return userId;
  }

  /**
   * The NIK fully redacted for a response/DTO. The NIK is highly sensitive (the national id), so it
   * is never partially revealed — a caller sees only that a value is present. The raw plaintext is
   * NEVER returned (rule 6).
   */
  public String maskedNik() {
    return PiiMasking.redact(nik);
  }

  /**
   * The bank account masked to its last 4 digits (e.g. {@code "****6789"}) for a response/DTO. The
   * raw plaintext is NEVER returned (rule 6).
   */
  public String maskedBankAccount() {
    return PiiMasking.lastFour(bankAccount);
  }

  /**
   * Whether an NPWP is on file — the payroll engine's no-PII signal for the UU PPh Art 21(5a)
   * no-NPWP x120% surcharge. NOT the plaintext.
   */
  public boolean hasNpwp() {
    return npwp != null && !npwp.isBlank();
  }

  /**
   * The NPWP fully redacted for a response/DTO (mirrors {@link #maskedNik()} — too sensitive to
   * partially reveal), or {@code null} when none is on file. The raw plaintext is NEVER returned
   * (rule 6).
   */
  public String maskedNpwp() {
    return PiiMasking.redact(npwp);
  }

  /**
   * The raw decrypted NIK. <strong>Restricted:</strong> intended only for the encrypt/decrypt path
   * and authorized internal use — it is never placed in a response, log, or event. Package-private
   * so only the feature can reach it (e.g. a round-trip test in the same package).
   */
  String nikPlaintext() {
    return nik;
  }

  /** The raw decrypted bank account. Restricted exactly as {@link #nikPlaintext()}. */
  String bankAccountPlaintext() {
    return bankAccount;
  }

  /**
   * The raw decrypted bank account, for the {@code payroll.service.BankFileReader} net-pay bank
   * file export ONLY (Track P phase P5) — the single OTHER authorized boundary besides the
   * encrypt/decrypt round-trip test and {@link #bankAccountPlaintext()}. A deliberate, minimal
   * PUBLIC widening (a different feature package needs it) documented at the call site too: the
   * decrypted value crosses this boundary and is placed directly into the CSV response body, NEVER
   * logged (rule 6) — {@code BankFileReader}'s audit line carries only {@code runId} + a row count.
   */
  public String bankAccountForBankFile() {
    return bankAccount;
  }

  /**
   * The raw decrypted NPWP, or null if none is on file. Restricted exactly as {@link
   * #nikPlaintext()}.
   */
  String npwpPlaintext() {
    return npwp;
  }

  /**
   * A PII-safe representation: id / status only, NEVER the name, NIK, or bank account (rule 6 — PII
   * never logged). An accidental {@code log.info("emp={}", employee)} cannot leak PII.
   */
  @Override
  public String toString() {
    return "Employee[id=" + id + ", status=" + status + "]";
  }
}
