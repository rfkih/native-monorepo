package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.domain.Customer;
import id.co.nativeapp.finance.ar.domain.CustomerNotFoundException;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.repository.CustomerRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for {@link Customer} create/update. A distinct bean (not
 * a private method on a service) so it is invoked through the Spring proxy — the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that
 * binds the tenant GUC only apply through the proxy, and the GUC is what makes the RLS {@code WITH
 * CHECK} pass on the insert (rule 5). The tenant is bound at the request edge (gateway → security
 * filter → {@link TenantContext}).
 */
@Component
public class CustomerWriter {

  private final CustomerRepository customerRepository;

  public CustomerWriter(CustomerRepository customerRepository) {
    this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository");
  }

  /**
   * Creates an active customer in the bound tenant. Returns the response DTO (never the entity).
   */
  @Transactional
  public CustomerResponse create(String name, String email, String taxId) {
    Customer customer = Customer.create(name, email, taxId);
    customer.setCompanyId(TenantContext.require().companyId());
    return toResponse(customerRepository.save(customer));
  }

  /**
   * Updates a customer's contact fields and/or active flag. A null {@code active} leaves the flag
   * unchanged.
   *
   * @throws CustomerNotFoundException if the id is not in the bound tenant (RLS-scoped)
   */
  @Transactional
  public CustomerResponse update(
      UUID customerId, String name, String email, String taxId, Boolean active) {
    Customer customer =
        customerRepository
            .findById(customerId)
            .orElseThrow(() -> new CustomerNotFoundException(customerId));
    customer.updateDetails(name, email, taxId);
    if (active != null) {
      customer.setActive(active);
    }
    return toResponse(customerRepository.save(customer));
  }

  private static CustomerResponse toResponse(Customer customer) {
    return new CustomerResponse(
        customer.getId(),
        customer.getName(),
        customer.getEmail(),
        customer.getTaxId(),
        customer.isActive());
  }
}
