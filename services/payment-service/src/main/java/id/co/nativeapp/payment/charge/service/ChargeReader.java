package id.co.nativeapp.payment.charge.service;

import id.co.nativeapp.payment.charge.domain.ChargeNotFoundException;
import id.co.nativeapp.payment.charge.projection.ChargeView;
import id.co.nativeapp.payment.charge.repository.PaymentChargeRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the charge lifecycle — the till's poll, served from the {@link ChargeView}
 * projection (ADR 0002). {@code @Transactional} so the RLS aspect binds the tenant GUC.
 */
@Component
public class ChargeReader {

  private final PaymentChargeRepository repository;

  public ChargeReader(PaymentChargeRepository repository) {
    this.repository = repository;
  }

  /** The poll view; unknown/cross-tenant → 404. */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public ChargeView view(UUID chargeId) {
    TenantContext.require();
    return repository.findViewById(chargeId).orElseThrow(ChargeNotFoundException::new);
  }
}
