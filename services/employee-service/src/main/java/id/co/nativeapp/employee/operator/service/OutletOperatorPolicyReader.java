package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.operator.domain.OutletOperatorPolicy;
import id.co.nativeapp.employee.operator.repository.OutletOperatorPolicyRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only, RLS-scoped lookup of the per-outlet operator-PIN policy (ADR 0049) — used by {@code
 * GET /api/v1/operators/policy}, the operator-session mint ({@code OperatorSessionWriter}), and the
 * policy-aware roster ({@code OperatorRosterService}).
 *
 * <p>A separate {@code @Component} (not a private method on a caller) so the read-only transaction
 * is invoked through the Spring proxy and {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5) —
 * mirrors {@code DeviceCredentialReader}. When called from within an already-{@code @Transactional}
 * caller (the common case here), this simply joins the existing transaction (default {@code
 * REQUIRED} propagation) — the aspect's GUC registration is idempotent either way.
 */
@Component
public class OutletOperatorPolicyReader {

  private final OutletOperatorPolicyRepository outletOperatorPolicyRepository;

  public OutletOperatorPolicyReader(OutletOperatorPolicyRepository outletOperatorPolicyRepository) {
    this.outletOperatorPolicyRepository = outletOperatorPolicyRepository;
  }

  /**
   * Whether {@code businessId} currently requires a PIN for operator sign-in. An outlet with no
   * policy row on file defaults to {@code true} (the safe default — today's behavior).
   */
  @Transactional(readOnly = true)
  public boolean requirePin(UUID businessId) {
    return outletOperatorPolicyRepository
        .findByBusinessId(businessId)
        .map(OutletOperatorPolicy::isRequirePin)
        .orElse(true);
  }
}
