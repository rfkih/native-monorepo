package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.VendorNotFoundException;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.projection.VendorView;
import id.co.nativeapp.finance.ap.repository.VendorRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads vendors for the bound tenant — the query side of the vendor slice.
 * {@code @Transactional(readOnly = true)} so the proxy + auto-RLS aspect engage; the reads carry no
 * manual {@code WHERE company_id} (RLS scopes them, rule 5), and the projection type never leaves
 * this layer.
 */
@Service
public class VendorReader {

  private final VendorRepository vendorRepository;

  public VendorReader(VendorRepository vendorRepository) {
    this.vendorRepository = vendorRepository;
  }

  /** All vendors in the bound tenant, ordered by name. */
  @Transactional(readOnly = true)
  public List<VendorResponse> list() {
    return vendorRepository.findAllView().stream().map(VendorReader::toResponse).toList();
  }

  /**
   * One vendor by id.
   *
   * @throws VendorNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public VendorResponse get(UUID id) {
    return vendorRepository
        .findViewById(id)
        .map(VendorReader::toResponse)
        .orElseThrow(() -> new VendorNotFoundException(id));
  }

  private static VendorResponse toResponse(VendorView view) {
    return new VendorResponse(
        view.getId(), view.getName(), view.getEmail(), view.getTaxId(), view.getActive());
  }
}
