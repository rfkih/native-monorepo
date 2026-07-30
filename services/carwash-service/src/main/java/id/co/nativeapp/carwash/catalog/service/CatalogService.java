package id.co.nativeapp.carwash.catalog.service;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.config.CarwashProperties;
import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.entitlementcheck.EntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the carwash catalog (wash packages, addons, staff profiles) — the {@link
 * id.co.nativeapp.carwash.wash.service.WashService WashService} entitlement-gating pattern applied
 * to catalog management.
 *
 * <p><strong>Writes are entitlement-gated; reads are not.</strong> Every create/patch consults the
 * shared {@link EntitlementChecker} for the bound tenant and the configured {@code carwash} module
 * BEFORE any side effect; a company not entitled gets {@link NotEntitledException} → {@code 403} and
 * no row is written. Reads ({@link #listPackages}/{@link #listAddons}/{@link #listStaffProfiles})
 * are RLS-scoped only — browsing (or rendering the entitlement gate itself) never requires the
 * module grant.
 *
 * <p>Not itself {@code @Transactional}: writes delegate to {@link CatalogWriter}, reads delegate to
 * {@link CatalogReader} — each invoked through the Spring proxy so the tx advice and the RLS
 * auto-apply aspect engage.
 */
@Service
public class CatalogService {

  private final CatalogWriter writer;
  private final CatalogReader reader;
  private final EntitlementChecker entitlementChecker;
  private final String moduleKey;

  public CatalogService(
      CatalogWriter writer,
      CatalogReader reader,
      EntitlementChecker entitlementChecker,
      CarwashProperties properties) {
    this.writer = writer;
    this.reader = reader;
    this.entitlementChecker = entitlementChecker;
    this.moduleKey = properties.moduleKey();
  }

  public CatalogItemResponse createPackage(CatalogItemCreateRequest request) {
    requireEntitled();
    return writer.createPackage(request);
  }

  public CatalogItemResponse patchPackage(UUID id, CatalogItemPatchRequest request) {
    requireEntitled();
    return writer.patchPackage(id, request);
  }

  public List<CatalogItemResponse> listPackages(UUID businessId, boolean activeOnly) {
    return reader.listPackages(businessId, activeOnly);
  }

  public CatalogItemResponse createAddon(CatalogItemCreateRequest request) {
    requireEntitled();
    return writer.createAddon(request);
  }

  public CatalogItemResponse patchAddon(UUID id, CatalogItemPatchRequest request) {
    requireEntitled();
    return writer.patchAddon(id, request);
  }

  public List<CatalogItemResponse> listAddons(UUID businessId, boolean activeOnly) {
    return reader.listAddons(businessId, activeOnly);
  }

  public StaffProfileResponse createStaffProfile(StaffProfileCreateRequest request) {
    requireEntitled();
    return writer.createStaffProfile(request);
  }

  public StaffProfileResponse patchStaffProfile(UUID id, StaffProfilePatchRequest request) {
    requireEntitled();
    return writer.patchStaffProfile(id, request);
  }

  public List<StaffProfileResponse> listStaffProfiles(UUID businessId, boolean activeOnly) {
    return reader.listStaffProfiles(businessId, activeOnly);
  }

  /**
   * The entitlement gate (Phase-2 pattern): rejects a write with {@code 403} if the bound company is
   * not entitled to the {@code carwash} module. Checked BEFORE the write so no row is produced on a
   * denied request.
   */
  private void requireEntitled() {
    String companyId = TenantContext.require().companyId();
    if (!entitlementChecker.isEntitled(companyId, moduleKey)) {
      throw new NotEntitledException(moduleKey);
    }
  }
}
