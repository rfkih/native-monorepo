package id.co.nativeapp.org.devicecredential.service;

import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import id.co.nativeapp.org.devicecredential.repository.DeviceCredentialRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write path for {@link DeviceCredential} rows.
 *
 * <p>A distinct {@code @Component} (not private methods on {@link DeviceCredentialService}) so
 * every method is invoked through the Spring proxy and {@link RlsAutoApplyAspect} sets the tenant
 * GUC (rule 5) — the same {@code *Writer} pattern {@code UserOutletAssignmentWriter} uses. Each
 * method is its own {@code REQUIRES_NEW} unit of work so a caller orchestrating this alongside the
 * (out-of-band) Keycloak call can compensate precisely on partial failure — mirroring {@code
 * SignupService}'s Keycloak-first-then-compensate pattern.
 */
@Component
public class DeviceCredentialWriter {

  private final DeviceCredentialRepository repository;

  public DeviceCredentialWriter(DeviceCredentialRepository repository) {
    this.repository = repository;
  }

  /**
   * Inserts a new device-credential row for the bound tenant.
   *
   * @throws DeviceCredentialAlreadyExistsException a row for this outlet already exists — the
   *     {@code UNIQUE(company_id, org_unit_id)} constraint (V11) caught a concurrent create race
   *     (the common case is already rejected earlier by {@link
   *     DeviceCredentialReader#existsForOutlet})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeviceCredential persist(
      UUID outletId, String keycloakUserId, String username, String password) {
    DeviceCredential credential =
        new DeviceCredential(outletId, keycloakUserId, username, password);
    credential.setCompanyId(TenantContext.require().companyId());
    try {
      DeviceCredential saved = repository.save(credential);
      repository.flush();
      return saved;
    } catch (DataIntegrityViolationException e) {
      throw new DeviceCredentialAlreadyExistsException(outletId);
    }
  }

  /**
   * Rotates the stored password for the outlet's device credential (a reset).
   *
   * @throws DeviceCredentialNotFoundException no credential exists for this outlet
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rotatePassword(UUID outletId, String newPassword) {
    DeviceCredential credential =
        repository
            .findByOrgUnitId(outletId)
            .orElseThrow(() -> new DeviceCredentialNotFoundException(outletId));
    credential.rotatePassword(newPassword);
    repository.save(credential);
  }

  /** Removes the device-credential row for the outlet, if any (idempotent — a no-op if absent). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void delete(UUID outletId) {
    repository.findByOrgUnitId(outletId).ifPresent(repository::delete);
    repository.flush();
  }
}
