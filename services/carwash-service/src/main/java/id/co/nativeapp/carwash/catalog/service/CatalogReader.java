package id.co.nativeapp.carwash.catalog.service;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.catalog.projection.CatalogItemView;
import id.co.nativeapp.carwash.catalog.projection.StaffProfileView;
import id.co.nativeapp.carwash.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.carwash.catalog.repository.WashAddonRepository;
import id.co.nativeapp.carwash.catalog.repository.WashPackageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional(readOnly = true)} catalog list reads. A distinct bean from {@link
 * CatalogWriter} and {@link CatalogService} so it is invoked through the Spring proxy — the
 * {@code @Transactional} advice and {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that binds
 * the tenant GUC only apply through the proxy.
 *
 * <p>Deliberately UNGATED by the carwash entitlement (unlike writes): browsing the catalog carries
 * no revenue/side-effect risk, and the checkout screen needs to render the (possibly-not-yet-
 * entitled) catalog to show the entitlement gate itself. Every method is RLS-scoped only.
 */
@Service
public class CatalogReader {

  private final WashPackageRepository packageRepository;
  private final WashAddonRepository addonRepository;
  private final StaffProfileRepository staffProfileRepository;

  public CatalogReader(
      WashPackageRepository packageRepository,
      WashAddonRepository addonRepository,
      StaffProfileRepository staffProfileRepository) {
    this.packageRepository = packageRepository;
    this.addonRepository = addonRepository;
    this.staffProfileRepository = staffProfileRepository;
  }

  @Transactional(readOnly = true)
  public List<CatalogItemResponse> listPackages(UUID businessId, boolean activeOnly) {
    return packageRepository.findViews(businessId, activeOnly).stream()
        .map(CatalogReader::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<CatalogItemResponse> listAddons(UUID businessId, boolean activeOnly) {
    return addonRepository.findViews(businessId, activeOnly).stream()
        .map(CatalogReader::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<StaffProfileResponse> listStaffProfiles(UUID businessId, boolean activeOnly) {
    return staffProfileRepository.findViews(businessId, activeOnly).stream()
        .map(CatalogReader::toResponse)
        .toList();
  }

  /** Maps a read-path {@link CatalogItemView} projection to the response shape. */
  static CatalogItemResponse toResponse(CatalogItemView view) {
    return new CatalogItemResponse(
        view.getId(),
        view.getBusinessId(),
        view.getName(),
        view.getDescription(),
        view.getPriceMinor(),
        view.getCurrency().strip(),
        view.isActive(),
        view.getDisplayOrder());
  }

  /** Maps a read-path {@link StaffProfileView} projection to the response shape. */
  static StaffProfileResponse toResponse(StaffProfileView view) {
    return new StaffProfileResponse(
        view.getId(),
        view.getBusinessId(),
        view.getDisplayLabel(),
        view.getEmployeeId(),
        view.isActive());
  }
}
