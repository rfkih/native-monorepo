package id.co.nativeapp.carwash.catalog.service;

import id.co.nativeapp.carwash.catalog.domain.CatalogItemNotFoundException;
import id.co.nativeapp.carwash.catalog.domain.StaffProfile;
import id.co.nativeapp.carwash.catalog.domain.WashAddon;
import id.co.nativeapp.carwash.catalog.domain.WashPackage;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.carwash.catalog.repository.WashAddonRepository;
import id.co.nativeapp.carwash.catalog.repository.WashPackageRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} catalog write units of work: create/patch for {@code
 * wash_package}, {@code wash_addon}, and {@code staff_profile}. A distinct bean from {@link
 * CatalogService} so it is invoked through the Spring proxy — the {@code @Transactional} advice and
 * {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that binds the tenant GUC only apply through
 * the proxy.
 *
 * <p>The write path loads the FULL entity (inherited {@code findById}/{@code save}) — it
 * legitimately needs the whole aggregate to mutate and persist it, and the freshly-saved entity
 * maps directly to the response (never a projection — that pattern is reserved for reads, mirroring
 * {@code WashResponse.from(Wash)}). A cross-tenant / unknown id is indistinguishable (RLS makes it
 * invisible) and surfaces as {@link CatalogItemNotFoundException} → {@code 404}. A duplicate {@code
 * staff_profile} display label surfaces as a {@code DataIntegrityViolationException} from the
 * unique constraint → {@code 409} (mapped by {@code config.CatalogAdvice}), uncaught here.
 */
@Component
public class CatalogWriter {

  private final WashPackageRepository packageRepository;
  private final WashAddonRepository addonRepository;
  private final StaffProfileRepository staffProfileRepository;

  public CatalogWriter(
      WashPackageRepository packageRepository,
      WashAddonRepository addonRepository,
      StaffProfileRepository staffProfileRepository) {
    this.packageRepository = packageRepository;
    this.addonRepository = addonRepository;
    this.staffProfileRepository = staffProfileRepository;
  }

  @Transactional
  public CatalogItemResponse createPackage(CatalogItemCreateRequest request) {
    WashPackage item =
        new WashPackage(
            request.businessId(),
            request.name(),
            request.description(),
            Money.ofMinor(request.priceMinor(), request.currency()),
            0);
    item.setCompanyId(TenantContext.require().companyId());
    return toResponse(packageRepository.saveAndFlush(item));
  }

  @Transactional
  public CatalogItemResponse patchPackage(UUID id, CatalogItemPatchRequest request) {
    WashPackage item =
        packageRepository.findById(id).orElseThrow(() -> new CatalogItemNotFoundException(id));
    if (request.name() != null) {
      item.rename(request.name());
    }
    if (request.description() != null) {
      item.updateDescription(request.description());
    }
    if (request.priceMinor() != null) {
      item.reprice(
          Money.ofMinor(request.priceMinor(), item.getPrice().currency().getCurrencyCode()));
    }
    if (request.active() != null) {
      item.setActive(request.active());
    }
    if (request.displayOrder() != null) {
      item.setDisplayOrder(request.displayOrder());
    }
    return toResponse(packageRepository.saveAndFlush(item));
  }

  @Transactional
  public CatalogItemResponse createAddon(CatalogItemCreateRequest request) {
    WashAddon item =
        new WashAddon(
            request.businessId(),
            request.name(),
            request.description(),
            Money.ofMinor(request.priceMinor(), request.currency()),
            0);
    item.setCompanyId(TenantContext.require().companyId());
    return toResponse(addonRepository.saveAndFlush(item));
  }

  @Transactional
  public CatalogItemResponse patchAddon(UUID id, CatalogItemPatchRequest request) {
    WashAddon item =
        addonRepository.findById(id).orElseThrow(() -> new CatalogItemNotFoundException(id));
    if (request.name() != null) {
      item.rename(request.name());
    }
    if (request.description() != null) {
      item.updateDescription(request.description());
    }
    if (request.priceMinor() != null) {
      item.reprice(
          Money.ofMinor(request.priceMinor(), item.getPrice().currency().getCurrencyCode()));
    }
    if (request.active() != null) {
      item.setActive(request.active());
    }
    if (request.displayOrder() != null) {
      item.setDisplayOrder(request.displayOrder());
    }
    return toResponse(addonRepository.saveAndFlush(item));
  }

  @Transactional
  public StaffProfileResponse createStaffProfile(StaffProfileCreateRequest request) {
    boolean active = request.active() == null || request.active();
    StaffProfile profile =
        new StaffProfile(
            request.businessId(), request.displayLabel(), request.employeeId(), active);
    profile.setCompanyId(TenantContext.require().companyId());
    return toResponse(staffProfileRepository.saveAndFlush(profile));
  }

  @Transactional
  public StaffProfileResponse patchStaffProfile(UUID id, StaffProfilePatchRequest request) {
    StaffProfile profile =
        staffProfileRepository.findById(id).orElseThrow(() -> new CatalogItemNotFoundException(id));
    if (request.displayLabel() != null) {
      profile.rename(request.displayLabel());
    }
    if (request.employeeId() != null) {
      profile.relink(request.employeeId());
    }
    if (request.active() != null) {
      profile.setActive(request.active());
    }
    return toResponse(staffProfileRepository.saveAndFlush(profile));
  }

  private static CatalogItemResponse toResponse(WashPackage item) {
    return new CatalogItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getDescription(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive(),
        item.getDisplayOrder());
  }

  private static CatalogItemResponse toResponse(WashAddon item) {
    return new CatalogItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getDescription(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive(),
        item.getDisplayOrder());
  }

  private static StaffProfileResponse toResponse(StaffProfile profile) {
    return new StaffProfileResponse(
        profile.getId(),
        profile.getBusinessId(),
        profile.getDisplayLabel(),
        profile.getEmployeeId(),
        profile.isActive());
  }
}
