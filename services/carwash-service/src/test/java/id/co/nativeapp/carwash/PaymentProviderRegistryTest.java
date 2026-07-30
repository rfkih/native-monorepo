package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.payment.service.CashProvider;
import id.co.nativeapp.carwash.payment.service.DigitalProvider;
import id.co.nativeapp.carwash.payment.service.PaymentInstruction;
import id.co.nativeapp.carwash.payment.service.PaymentProvider;
import id.co.nativeapp.carwash.payment.service.PaymentProviderRegistry;
import id.co.nativeapp.carwash.payment.service.TenderAuthorization;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests (no Spring context, no database) for the payment-provider port ported from
 * restaurant-service's {@code payment} feature: {@link CashProvider}, {@link DigitalProvider}, and
 * {@link PaymentProviderRegistry} routing (ADR 0006).
 *
 * <p>Covers exactly the three behaviours the port task calls out:
 *
 * <ul>
 *   <li>cash captures synchronously and computes exact change;
 *   <li>a digital tender (QRIS/CARD) returns a {@code PENDING} authorization (never moves money —
 *       ADR 0006/0007);
 *   <li>an unregistered/unknown tender is rejected by the registry.
 * </ul>
 */
class PaymentProviderRegistryTest {

  private static final UUID TICKET_ID = UUID.randomUUID();

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  private static PaymentInstruction instruction(
      TenderType tenderType, Money amount, Long tenderedMinor, String idempotencyKey) {
    return new PaymentInstruction(TICKET_ID, tenderType, amount, tenderedMinor, idempotencyKey);
  }

  // -----------------------------------------------------------------------
  // CashProvider — captures with change
  // -----------------------------------------------------------------------

  @Test
  void cashProviderCapturesAndComputesExactChange() {
    CashProvider provider = new CashProvider();
    PaymentInstruction instruction = instruction(TenderType.CASH, idr(83_000L), 100_000L, "idem-1");

    TenderAuthorization auth = provider.authorize(instruction);

    assertThat(auth.status()).isEqualTo(CarwashPayment.Status.CAPTURED);
    assertThat(auth.pending()).isFalse();
    assertThat(auth.providerRef()).isNull();
    assertThat(auth.change().amountMinor()).isEqualTo(17_000L);
  }

  @Test
  void cashProviderRejectsTenderedLessThanAmountDue() {
    CashProvider provider = new CashProvider();
    PaymentInstruction instruction = instruction(TenderType.CASH, idr(83_000L), 50_000L, "idem-2");

    assertThatThrownBy(() -> provider.authorize(instruction))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cashProviderRejectsAMissingTenderedAmount() {
    CashProvider provider = new CashProvider();
    PaymentInstruction instruction = instruction(TenderType.CASH, idr(10_000L), null, "idem-3");

    assertThatThrownBy(() -> provider.authorize(instruction))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tendered");
  }

  // -----------------------------------------------------------------------
  // DigitalProvider — flagged-pending, never moves money
  // -----------------------------------------------------------------------

  @Test
  void digitalProviderReturnsPendingWithADeterministicPlaceholderRef() {
    DigitalProvider provider = new DigitalProvider();
    PaymentInstruction instruction = instruction(TenderType.QRIS, idr(83_000L), null, "idem-4");

    TenderAuthorization auth = provider.authorize(instruction);

    assertThat(auth.status()).isEqualTo(CarwashPayment.Status.PENDING);
    assertThat(auth.pending()).isTrue();
    assertThat(auth.change()).isNull();
    assertThat(auth.providerRef()).startsWith("PENDING-idem-4-");
  }

  @Test
  void digitalProviderHandlesCardTooAndRejectsCash() {
    DigitalProvider provider = new DigitalProvider();

    TenderAuthorization cardAuth =
        provider.authorize(instruction(TenderType.CARD, idr(5_000L), null, "idem-5"));
    assertThat(cardAuth.status()).isEqualTo(CarwashPayment.Status.PENDING);

    assertThatThrownBy(
            () -> provider.authorize(instruction(TenderType.CASH, idr(5_000L), 5_000L, "idem-6")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -----------------------------------------------------------------------
  // PaymentProviderRegistry — routes by tender, rejects unknown tenders
  // -----------------------------------------------------------------------

  @Test
  void registryRoutesCashAndDigitalTendersToTheirProviders() {
    CashProvider cash = new CashProvider();
    DigitalProvider digital = new DigitalProvider();
    PaymentProviderRegistry registry = new PaymentProviderRegistry(List.of(cash, digital));

    assertThat(registry.providerFor(TenderType.CASH)).isSameAs(cash);
    assertThat(registry.providerFor(TenderType.QRIS)).isSameAs(digital);
    assertThat(registry.providerFor(TenderType.CARD)).isSameAs(digital);
  }

  @Test
  void registryRejectsAnUnknownTenderWhenNoProviderIsRegisteredForIt() {
    // Only cash is registered — QRIS/CARD have no provider (e.g. before ADR 0007 wires a real
    // adapter, or in a minimal test wiring).
    PaymentProviderRegistry registry = new PaymentProviderRegistry(List.of(new CashProvider()));

    assertThatThrownBy(() -> registry.providerFor(TenderType.QRIS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no payment provider");
  }

  @Test
  void registryRejectsTwoProvidersClaimingTheSameTender() {
    List<PaymentProvider> conflicting =
        List.of(
            new PaymentProvider() {
              @Override
              public java.util.Set<TenderType> supportedTenders() {
                return java.util.Set.of(TenderType.CASH);
              }

              @Override
              public TenderAuthorization authorize(PaymentInstruction instruction) {
                throw new UnsupportedOperationException("not used");
              }
            },
            new CashProvider());

    assertThatThrownBy(() -> new PaymentProviderRegistry(conflicting))
        .isInstanceOf(IllegalStateException.class);
  }
}
