package id.co.nativeapp.org.devicecredential.service;

import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import id.co.nativeapp.org.devicecredential.repository.DeviceCredentialRepository;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the device-credential feature: the target-outlet validation
 * shared by every endpoint, and the {@code GET}/existence reads.
 *
 * <p>A separate {@code @Component} (not private methods on {@link DeviceCredentialService}) so the
 * read-only transaction is invoked through the Spring proxy and {@link RlsAutoApplyAspect} sets the
 * tenant GUC (rule 5) — mirrors {@code OrgUnitUsersReader}.
 */
@Component
public class DeviceCredentialReader {

  private final OrgUnitRepository orgUnitRepository;
  private final DeviceCredentialRepository deviceCredentialRepository;

  public DeviceCredentialReader(
      OrgUnitRepository orgUnitRepository, DeviceCredentialRepository deviceCredentialRepository) {
    this.orgUnitRepository = orgUnitRepository;
    this.deviceCredentialRepository = deviceCredentialRepository;
  }

  /**
   * Resolves {@code outletId} to an OUTLET-type org unit visible in the bound tenant.
   *
   * @throws OrgUnitNotFoundException unknown or cross-tenant id (RLS makes the two
   *     indistinguishable — anti-enumeration) → {@code 404}
   * @throws DeviceCredentialTargetNotOutletException the unit exists but is not an OUTLET (a
   *     business unit or a team) → {@code 400}
   */
  @Transactional(readOnly = true)
  public OrgUnitView requireOutlet(UUID outletId) {
    OrgUnitView unit =
        orgUnitRepository
            .findViewById(outletId)
            .orElseThrow(() -> new OrgUnitNotFoundException(outletId));
    if (OrgUnitType.from(unit.getType()) != OrgUnitType.OUTLET) {
      throw new DeviceCredentialTargetNotOutletException(outletId, unit.getType());
    }
    return unit;
  }

  /** Whether a device credential already exists for {@code outletId}. */
  @Transactional(readOnly = true)
  public boolean existsForOutlet(UUID outletId) {
    return deviceCredentialRepository.existsByOrgUnitId(outletId);
  }

  /**
   * The device credential bound to {@code outletId}, within the bound tenant.
   *
   * @throws DeviceCredentialNotFoundException no credential exists for this outlet → {@code 404}
   */
  @Transactional(readOnly = true)
  public DeviceCredential requireCredential(UUID outletId) {
    return deviceCredentialRepository
        .findByOrgUnitId(outletId)
        .orElseThrow(() -> new DeviceCredentialNotFoundException(outletId));
  }
}
