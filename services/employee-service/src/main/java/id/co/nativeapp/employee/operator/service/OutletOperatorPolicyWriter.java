package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.operator.domain.OutletOperatorPolicy;
import id.co.nativeapp.employee.operator.repository.OutletOperatorPolicyRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work for owner/manager setting the per-outlet
 * operator-PIN policy (ADR 0049) — a distinct bean (not a private method on {@link
 * OutletOperatorPolicyService}) so the Spring proxy + {@link RlsAutoApplyAspect} engage (the {@code
 * *Writer} pattern, CODE-STRUCTURE §3.2). Mirrors {@code OperatorPinWriter}'s upsert shape.
 */
@Component
public class OutletOperatorPolicyWriter {

  private final OutletOperatorPolicyRepository outletOperatorPolicyRepository;

  public OutletOperatorPolicyWriter(OutletOperatorPolicyRepository outletOperatorPolicyRepository) {
    this.outletOperatorPolicyRepository = outletOperatorPolicyRepository;
  }

  /** Creates (first time) or updates (already set) the operator-PIN policy for an outlet. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void upsert(UUID businessId, boolean requirePin) {
    String companyId = TenantContext.require().companyId();

    OutletOperatorPolicy policy =
        outletOperatorPolicyRepository
            .findByBusinessId(businessId)
            .map(
                existing -> {
                  existing.setRequirePin(requirePin);
                  return existing;
                })
            .orElseGet(
                () -> {
                  OutletOperatorPolicy created = new OutletOperatorPolicy(businessId, requirePin);
                  created.setCompanyId(companyId);
                  return created;
                });

    outletOperatorPolicyRepository.save(policy);
    outletOperatorPolicyRepository.flush();
  }
}
