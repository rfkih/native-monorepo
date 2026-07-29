package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.Vendor;
import id.co.nativeapp.finance.ap.domain.VendorNotFoundException;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.repository.VendorRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for {@link Vendor} create/update. A distinct bean (not a
 * private method on a service) so it is invoked through the Spring proxy — the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that
 * binds the tenant GUC only apply through the proxy, and the GUC is what makes the RLS {@code WITH
 * CHECK} pass on the insert (rule 5). The tenant is bound at the request edge (gateway → security
 * filter → {@link TenantContext}).
 */
@Component
public class VendorWriter {

  private final VendorRepository vendorRepository;

  public VendorWriter(VendorRepository vendorRepository) {
    this.vendorRepository = Objects.requireNonNull(vendorRepository, "vendorRepository");
  }

  /** Creates an active vendor in the bound tenant. Returns the response DTO (never the entity). */
  @Transactional
  public VendorResponse create(String name, String email, String taxId) {
    Vendor vendor = Vendor.create(name, email, taxId);
    vendor.setCompanyId(TenantContext.require().companyId());
    return toResponse(vendorRepository.save(vendor));
  }

  /**
   * Updates a vendor's contact fields and/or active flag. A null {@code active} leaves the flag
   * unchanged.
   *
   * @throws VendorNotFoundException if the id is not in the bound tenant (RLS-scoped)
   */
  @Transactional
  public VendorResponse update(
      UUID vendorId, String name, String email, String taxId, Boolean active) {
    Vendor vendor =
        vendorRepository
            .findById(vendorId)
            .orElseThrow(() -> new VendorNotFoundException(vendorId));
    vendor.updateDetails(name, email, taxId);
    if (active != null) {
      vendor.setActive(active);
    }
    return toResponse(vendorRepository.save(vendor));
  }

  private static VendorResponse toResponse(Vendor vendor) {
    return new VendorResponse(
        vendor.getId(), vendor.getName(), vendor.getEmail(), vendor.getTaxId(), vendor.isActive());
  }
}
