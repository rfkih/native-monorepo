package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.payment.domain.TenderType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link PaymentProvider} for a tender. Built from every {@link PaymentProvider} bean
 * on the classpath, so wiring a real QRIS/card adapter (ADR 0007) is just a new bean — no edit
 * here.
 */
@Component
public class PaymentProviderRegistry {

  private final Map<TenderType, PaymentProvider> byTender = new EnumMap<>(TenderType.class);

  public PaymentProviderRegistry(List<PaymentProvider> providers) {
    for (PaymentProvider provider : providers) {
      for (TenderType tender : provider.supportedTenders()) {
        PaymentProvider prior = byTender.put(tender, provider);
        if (prior != null) {
          throw new IllegalStateException("two payment providers registered for tender " + tender);
        }
      }
    }
  }

  /**
   * @throws IllegalArgumentException if no provider is registered for the tender (e.g. a digital
   *     tender before its adapter is wired) — mapped to 400 by the API exception handler.
   */
  public PaymentProvider providerFor(TenderType tender) {
    PaymentProvider provider = byTender.get(tender);
    if (provider == null) {
      throw new IllegalArgumentException("no payment provider for tender: " + tender);
    }
    return provider;
  }
}
