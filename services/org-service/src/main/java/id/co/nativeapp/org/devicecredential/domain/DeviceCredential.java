package id.co.nativeapp.org.devicecredential.domain;

import id.co.nativeapp.org.config.PiiAttributeConverter;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The per-outlet Keycloak KIOSK/DEVICE login the Business app stays signed in as (ADR 0049 P3a).
 *
 * <p>Exactly one row per outlet ({@code UNIQUE(company_id, org_unit_id)}, V11). References the
 * Keycloak user ({@code keycloak_user_id}, the {@code sub}) that was minted with the {@code
 * cashier} realm role and the {@code actor_type=device} attribute — {@link
 * id.co.nativeapp.org.user.service.KeycloakAdminClient#createDeviceUser} owns that minting; this
 * aggregate only holds the local record for re-provisioning. The device is bound to its one outlet
 * via the EXISTING {@code user_outlet_assignment} table (V5) — no new binding mechanism.
 *
 * <p><strong>Credential at rest (rule 6).</strong> {@code password} is column-level encrypted via
 * {@link PiiAttributeConverter} (AES-256-GCM, random IV per value) — the database row only ever
 * holds ciphertext, while this aggregate works in plaintext. Unlike the ADR 0014 held-temp-password
 * (purged after first login), this credential is deliberately NOT purged: a kiosk login is
 * long-lived, and the owner/manager may need to re-reveal it to set up or replace a physical
 * device. The plaintext NEVER leaves this aggregate to a log or a {@link #toString()} — only the
 * owner/manager reveal endpoint exposes it, once per call, over TLS.
 *
 * <p>Extends {@link Auditable}, inheriting the mandatory audit + tenancy columns and the {@code
 * device_credential} RLS policy (rules 4 + 5).
 */
@Entity
@Table(name = "device_credential")
public class DeviceCredential extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The outlet (org_unit) this device is bound to. Must be an active OUTLET (service-enforced). */
  @Column(name = "org_unit_id", nullable = false, updatable = false)
  private UUID orgUnitId;

  /**
   * The Keycloak subject id (sub) of the minted device/kiosk user. Not PII (a stable opaque id).
   */
  @Column(name = "keycloak_user_id", nullable = false, updatable = false, length = 64)
  private String keycloakUserId;

  /** The device login's Keycloak username (e.g. {@code till.<outletId>}). Not PII. */
  @Column(name = "username", nullable = false, updatable = false, length = 255)
  private String username;

  /**
   * The device login's password — a CREDENTIAL, column-encrypted at rest via {@link
   * PiiAttributeConverter} exactly like employee-service's held temp password (rule 6). The
   * plaintext is held only in memory and never logged/serialized outside the owner/manager reveal
   * response.
   */
  @Convert(converter = PiiAttributeConverter.class)
  @Column(name = "password_enc", nullable = false)
  private String password;

  protected DeviceCredential() {
    // for JPA
  }

  /**
   * Creates a new device-credential row.
   *
   * @param orgUnitId the outlet this device is bound to; must be non-null
   * @param keycloakUserId the minted device user's Keycloak subject id; must be non-blank
   * @param username the device login's Keycloak username; must be non-blank
   * @param password the device login's password (plaintext at the application layer — encrypted on
   *     write by {@link PiiAttributeConverter}); must be non-blank — NEVER logged
   */
  public DeviceCredential(UUID orgUnitId, String keycloakUserId, String username, String password) {
    this.id = UUID.randomUUID();
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.keycloakUserId = requireNonBlank(keycloakUserId, "keycloakUserId");
    this.username = requireNonBlank(username, "username");
    this.password = requireNonBlank(password, "password");
  }

  /**
   * Replaces the stored password after a reset — the username and Keycloak user id are unchanged (a
   * reset rotates the credential, it does not re-provision the device).
   *
   * @param newPassword the freshly generated password — NEVER logged
   */
  public void rotatePassword(String newPassword) {
    this.password = requireNonBlank(newPassword, "password");
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

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public String getKeycloakUserId() {
    return keycloakUserId;
  }

  public String getUsername() {
    return username;
  }

  /** The decrypted password. Callers must NEVER log this — pass it only to a reveal-once DTO. */
  public String getPassword() {
    return password;
  }

  /**
   * A PII/credential-safe representation: id / outlet / keycloak-user-id / username only — NEVER
   * the password (rule 6). An accidental {@code log.info("cred={}", cred)} cannot leak it.
   */
  @Override
  public String toString() {
    return "DeviceCredential[id="
        + id
        + ", orgUnitId="
        + orgUnitId
        + ", keycloakUserId="
        + keycloakUserId
        + ", username="
        + username
        + ", password=***REDACTED***]";
  }
}
