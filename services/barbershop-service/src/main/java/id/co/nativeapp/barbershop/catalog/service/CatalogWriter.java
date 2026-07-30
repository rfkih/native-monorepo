package id.co.nativeapp.barbershop.catalog.service;

import id.co.nativeapp.barbershop.catalog.domain.CatalogItemNotFoundException;
import id.co.nativeapp.barbershop.catalog.domain.ServiceAddon;
import id.co.nativeapp.barbershop.catalog.domain.ServiceItem;
import id.co.nativeapp.barbershop.catalog.domain.StaffProfile;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.repository.ServiceAddonRepository;
import id.co.nativeapp.barbershop.catalog.repository.ServiceItemRepository;
import id.co.nativeapp.barbershop.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} catalog write units of work: create/patch for {@code
 * service_item}, {@code service_addon}, and {@code staff_profile}. A distinct bean from {@link
 * CatalogService} so it is invoked through the Spring proxy — the {@code @Transactional} advice and
 * {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that binds the tenant GUC only apply through
 * the proxy.
 *
 * <p>The write path loads the FULL entity (inherited {@code findById}/{@code save}) — it
 * legitimately needs the whole aggregate to mutate and persist it, and the freshly-saved entity
 * maps directly to the response (never a projection — that pattern is reserved for reads). A
 * cross-tenant / unknown id is indistinguishable (RLS makes it invisible) and surfaces as {@link
 * CatalogItemNotFoundException} → {@code 404}. A duplicate {@code staff_profile} display label
 * surfaces as a {@code DataIntegrityViolationException} from the unique constraint → {@code 409}
 * (mapped by {@code config.CatalogAdvice}), uncaught here.
 *
 * <p>{@code durationMinutes} on {@link CatalogItemCreateRequest}/{@link CatalogItemPatchRequest} is
 * PERSISTED for {@link #createService}/{@link #patchService} ({@code service_item} only) and
 * silently IGNORED by {@link #createAddon}/{@link #patchAddon} — {@code service_addon} has no such
 * column (see {@code ServiceAddon}'s javadoc).
 */
@Component
public class CatalogWriter {

  private final ServiceItemRepository serviceItemRepository;
  private final ServiceAddonRepository addonRepository;
  private final StaffProfileRepository staffProfileRepository;

  public CatalogWriter(
      ServiceItemRepository serviceItemRepository,
      ServiceAddonRepository addonRepository,
      StaffProfileRepository staffProfileRepository) {
    this.serviceItemRepository = serviceItemRepository;
    this.addonRepository = addonRepository;
    this.staffProfileRepository = staffProfileRepository;
  }

  @Transactional
  public CatalogItemResponse createService(CatalogItemCreateRequest request) {
    ServiceItem item =
        new ServiceItem(
            request.businessId(),
            request.name(),
            request.description(),
            Money.ofMinor(request.priceMinor(), request.currency()),
            request.durationMinutes(),
            0);
    item.setCompanyId(TenantContext.require().companyId());
    return toResponse(serviceItemRepository.saveAndFlush(item));
  }

  @Transactional
  public CatalogItemResponse patchService(UUID id, CatalogItemPatchRequest request) {
    ServiceItem item =
        serviceItemRepository.findById(id).orElseThrow(() -> new CatalogItemNotFoundException(id));
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
    if (request.durationMinutes() != null) {
      item.updateDurationMinutes(request.durationMinutes());
    }
    if (request.active() != null) {
      item.setActive(request.active());
    }
    if (request.displayOrder() != null) {
      item.setDisplayOrder(request.displayOrder());
    }
    return toResponse(serviceItemRepository.saveAndFlush(item));
  }

  @Transactional
  public CatalogItemResponse createAddon(CatalogItemCreateRequest request) {
    ServiceAddon item =
        new ServiceAddon(
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
    ServiceAddon item =
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

  private static CatalogItemResponse toResponse(ServiceItem item) {
    return new CatalogItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getDescription(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive(),
        item.getDisplayOrder(),
        item.getDurationMinutes());
  }

  private static CatalogItemResponse toResponse(ServiceAddon item) {
    return new CatalogItemResponse(
        item.getId(),
        item.getBusinessId(),
        item.getName(),
        item.getDescription(),
        item.getPrice().amountMinor(),
        item.getPrice().currency().getCurrencyCode(),
        item.isActive(),
        item.getDisplayOrder(),
        null);
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
