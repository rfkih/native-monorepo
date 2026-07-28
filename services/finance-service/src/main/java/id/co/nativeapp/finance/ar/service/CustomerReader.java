package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.domain.CustomerNotFoundException;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.projection.CustomerView;
import id.co.nativeapp.finance.ar.repository.CustomerRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads customers for the bound tenant — the query side of the customer slice.
 * {@code @Transactional(readOnly = true)} so the proxy + auto-RLS aspect engage; the reads carry no
 * manual {@code WHERE company_id} (RLS scopes them, rule 5), and the projection type never leaves
 * this layer.
 */
@Service
public class CustomerReader {

  private final CustomerRepository customerRepository;

  public CustomerReader(CustomerRepository customerRepository) {
    this.customerRepository = customerRepository;
  }

  /** All customers in the bound tenant, ordered by name. */
  @Transactional(readOnly = true)
  public List<CustomerResponse> list() {
    return customerRepository.findAllView().stream().map(CustomerReader::toResponse).toList();
  }

  /**
   * One customer by id.
   *
   * @throws CustomerNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public CustomerResponse get(UUID id) {
    return customerRepository
        .findViewById(id)
        .map(CustomerReader::toResponse)
        .orElseThrow(() -> new CustomerNotFoundException(id));
  }

  private static CustomerResponse toResponse(CustomerView view) {
    return new CustomerResponse(
        view.getId(), view.getName(), view.getEmail(), view.getTaxId(), view.getActive());
  }
}
