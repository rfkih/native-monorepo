package id.co.nativeapp.org.devicecredential.service;

import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import id.co.nativeapp.org.devicecredential.dto.DeviceCredentialResponse;
import id.co.nativeapp.org.user.service.KeycloakAdminClient;
import id.co.nativeapp.org.user.service.KeycloakAdminClient.DeviceCredentialResult;
import id.co.nativeapp.org.user.service.UserOutletAssignmentWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the per-outlet device (kiosk) credential lifecycle (ADR 0049 P3a): mint the Keycloak
 * {@code cashier}/{@code actor_type=device} login, bind it to exactly one outlet via the EXISTING
 * {@link UserOutletAssignmentWriter}, and hold the generated password encrypted for later
 * re-reveal.
 *
 * <p><strong>NOT {@code @Transactional}.</strong> Like {@code SignupService}, this service
 * orchestrates across two subsystems (Keycloak and the org-service DB); the units of work that MUST
 * be transactional ({@link DeviceCredentialWriter}, {@link UserOutletAssignmentWriter}) are already
 * {@code @Transactional} via their own proxies.
 *
 * <p><strong>Ordering + failure compensation (create).</strong> The Keycloak user is created FIRST
 * (after the outlet-type + duplicate pre-checks, so a doomed request never reaches Keycloak). If
 * either local DB write that follows fails, the already-created Keycloak user (and, if it got that
 * far, the local row) is compensated away — mirroring {@code SignupService}'s Keycloak-first-then-
 * compensate pattern. A failed compensation is the accepted double-failure residual: an orphaned
 * Keycloak user, logged at ERROR (user id only — never the password) for manual cleanup.
 *
 * <p><strong>Ordering (delete).</strong> The Keycloak user is deleted FIRST — revoking the ability
 * to authenticate immediately — before the local outlet-assignment close and row delete. Every step
 * is independently idempotent ({@link KeycloakAdminClient#deleteUser} treats a 404 as success; the
 * assignment close and row delete are no-ops if already done), so a caller retry after a partial
 * failure converges without a dedicated compensation path.
 *
 * <p><strong>Credential hygiene (rule 6).</strong> The generated/decrypted password is NEVER
 * logged; only stable, non-PII identifiers (outlet id, Keycloak user id) appear in log statements.
 */
@Service
public class DeviceCredentialService {

  private static final Logger log = LoggerFactory.getLogger(DeviceCredentialService.class);

  /** The Keycloak username prefix — {@code till.<outletId>} is stable/unique by construction. */
  private static final String USERNAME_PREFIX = "till.";

  private final DeviceCredentialReader reader;
  private final DeviceCredentialWriter writer;
  private final KeycloakAdminClient keycloak;
  private final UserOutletAssignmentWriter outletAssignmentWriter;

  public DeviceCredentialService(
      DeviceCredentialReader reader,
      DeviceCredentialWriter writer,
      KeycloakAdminClient keycloak,
      UserOutletAssignmentWriter outletAssignmentWriter) {
    this.reader = reader;
    this.writer = writer;
    this.keycloak = keycloak;
    this.outletAssignmentWriter = outletAssignmentWriter;
  }

  /**
   * Provisions a new device credential for {@code outletId}: mints the Keycloak user, binds it to
   * the outlet, and stores the password encrypted. Returns the username + PLAINTEXT password ONCE.
   *
   * @throws DeviceCredentialTargetNotOutletException {@code outletId} is not an OUTLET → {@code
   *     400}
   * @throws id.co.nativeapp.org.user.service.OrgUnitNotFoundException unknown/cross-tenant outlet →
   *     {@code 404}
   * @throws DeviceCredentialAlreadyExistsException a credential already exists for this outlet →
   *     {@code 409}
   */
  public DeviceCredentialResponse create(UUID outletId) {
    TenantContext.Tenant tenant = TenantContext.require();
    reader.requireOutlet(outletId); // 404 unknown/cross-tenant; 400 not-an-outlet
    if (reader.existsForOutlet(outletId)) {
      throw new DeviceCredentialAlreadyExistsException(outletId);
    }

    String username = USERNAME_PREFIX + outletId;
    DeviceCredentialResult kcResult = keycloak.createDeviceUser(username, tenant.companyId());

    boolean rowPersisted = false;
    try {
      writer.persist(outletId, kcResult.userId(), username, kcResult.password());
      rowPersisted = true;
      // Binds the device to exactly ONE outlet via the EXISTING writer — no new binding
      // mechanism; the device is never in the grandfather "0 rows -> all outlets" branch.
      outletAssignmentWriter.replaceAssignments(kcResult.userId(), List.of(outletId));
    } catch (RuntimeException e) {
      if (rowPersisted) {
        safeDeleteRow(outletId);
      }
      safeDeleteKeycloakUser(kcResult.userId());
      throw e;
    }

    log.info(
        "Device credential created: outletId={}, keycloakUserId={}", outletId, kcResult.userId());
    return new DeviceCredentialResponse(outletId, username, kcResult.password());
  }

  /**
   * Regenerates the device credential's password. Returns the username + new PLAINTEXT password
   * ONCE.
   *
   * @throws DeviceCredentialNotFoundException no credential exists for this outlet → {@code 404}
   */
  public DeviceCredentialResponse reset(UUID outletId) {
    TenantContext.require();
    DeviceCredential credential = reader.requireCredential(outletId);
    String newPassword = keycloak.resetDevicePassword(credential.getKeycloakUserId());
    writer.rotatePassword(outletId, newPassword);
    log.info("Device credential password reset: outletId={}", outletId);
    return new DeviceCredentialResponse(outletId, credential.getUsername(), newPassword);
  }

  /**
   * Reveals the stored username + decrypted password, for re-provisioning a physical device.
   *
   * @throws DeviceCredentialNotFoundException no credential exists for this outlet → {@code 404}
   */
  public DeviceCredentialResponse reveal(UUID outletId) {
    TenantContext.Tenant tenant = TenantContext.require();
    DeviceCredential credential = reader.requireCredential(outletId);
    // Audit the disclosure (P3a LOW-1) — a reveal is a read, so it leaves no Auditable/CDC trail on
    // its own; log WHO revealed WHICH outlet's credential (never the password itself, rule 6).
    log.info(
        "Device credential revealed: outletId={}, by={}, keycloakUserId={}",
        outletId,
        tenant.actor(),
        credential.getKeycloakUserId());
    return new DeviceCredentialResponse(
        outletId, credential.getUsername(), credential.getPassword());
  }

  /**
   * Permanently removes the device credential: the Keycloak login (deleted FIRST — see class
   * javadoc), its outlet assignment (closed), and the local row.
   *
   * @throws DeviceCredentialNotFoundException no credential exists for this outlet → {@code 404}
   */
  public void delete(UUID outletId) {
    TenantContext.require();
    DeviceCredential credential = reader.requireCredential(outletId);
    keycloak.deleteUser(credential.getKeycloakUserId());
    outletAssignmentWriter.replaceAssignments(credential.getKeycloakUserId(), List.of());
    writer.delete(outletId);
    log.info("Device credential deleted: outletId={}", outletId);
  }

  // ---------------------------------------------------------------------------
  // Create-path compensation (never throws — the original failure must propagate)
  // ---------------------------------------------------------------------------

  private void safeDeleteRow(UUID outletId) {
    try {
      writer.delete(outletId);
    } catch (RuntimeException cleanupFailure) {
      log.error(
          "device-credential create compensation: failed to remove the local row for outlet {};"
              + " manual cleanup required",
          outletId,
          cleanupFailure);
    }
  }

  private void safeDeleteKeycloakUser(String keycloakUserId) {
    try {
      keycloak.deleteUser(keycloakUserId);
      log.warn(
          "device-credential create: rolled back Keycloak user {} after a failed step",
          keycloakUserId);
    } catch (RuntimeException cleanupFailure) {
      log.error(
          "device-credential create compensation failed — orphaned Keycloak user {} remains;"
              + " manual cleanup required",
          keycloakUserId,
          cleanupFailure);
    }
  }
}
