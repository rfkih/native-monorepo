package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.payment.domain.TenderType;
import java.util.Set;

/**
 * The payment-tender port (ADR 0006). One implementation per rail, selected by tender via {@link
 * PaymentProviderRegistry}, so a real payment-service-provider is added as one new bean (ADR 0007)
 * with no {@code switch} to touch.
 *
 * <p>{@code CashProvider} is live (captures synchronously). The digital providers (QRIS/card) are
 * flagged-pending: they authorize a {@code PENDING} tender and never move money until a real
 * adapter lands.
 */
public interface PaymentProvider {

  /** The tender types this provider handles. */
  Set<TenderType> supportedTenders();

  /** Authorizes the instruction, returning the resulting status, reference, and (cash) change. */
  TenderAuthorization authorize(PaymentInstruction instruction);
}
