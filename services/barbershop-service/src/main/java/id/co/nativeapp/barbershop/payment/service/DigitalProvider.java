package id.co.nativeapp.barbershop.payment.service;

import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The flagged-pending digital-tender provider for QRIS and CARD (ADR 0006 / ADR 0007), ported
 * verbatim from carwash-service's {@code payment} feature.
 *
 * <p>In this phase no real PSP adapter exists, so the provider returns a {@link
 * BarbershopPayment.Status#PENDING} authorization with {@code providerPending = true} and a
 * deterministic placeholder {@code providerRef}. The placeholder is stable across retries with the
 * same instruction (keyed to the idempotency key) so a re-delivered checkout yields the same ref.
 *
 * <p>Revenue is recognised only when the ticket-checkout capture path runs: it will transition the
 * payment to {@link BarbershopPayment.Status#CAPTURED}. An abandoned digital tender — one that was
 * authorised here but never captured — never produces revenue (ADR 0006 invariant).
 *
 * <p>When ADR 0007 lands, a real QRIS provider replaces this bean for {@link TenderType#QRIS}, and
 * a real card provider for {@link TenderType#CARD}, without touching any other slice.
 */
@Component
public class DigitalProvider implements PaymentProvider {

  @Override
  public Set<TenderType> supportedTenders() {
    return Set.of(TenderType.QRIS, TenderType.CARD);
  }

  /**
   * Returns a {@link BarbershopPayment.Status#PENDING} authorization with a placeholder provider
   * reference. The placeholder is deterministic: {@code PENDING-<idempotency_key>-<random_uuid>}.
   * No external call is made; the payment rails are not yet wired (ADR 0007).
   */
  @Override
  public TenderAuthorization authorize(PaymentInstruction instruction) {
    if (instruction.tenderType() == TenderType.CASH) {
      throw new IllegalArgumentException("DigitalProvider does not handle CASH; use CashProvider");
    }
    // Stable, human-readable placeholder reference — the ADR 0007 adapter will replace this
    // with a real provider ref from the PSP gateway.
    String providerRef = "PENDING-" + instruction.idempotencyKey() + "-" + UUID.randomUUID();
    // change is null for digital tenders (no cash is handed over).
    return new TenderAuthorization(BarbershopPayment.Status.PENDING, providerRef, true, null);
  }
}
