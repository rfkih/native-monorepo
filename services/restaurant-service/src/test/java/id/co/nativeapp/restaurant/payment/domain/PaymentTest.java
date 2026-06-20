package id.co.nativeapp.restaurant.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain invariants for the {@link Payment} aggregate (no Spring / DB) — the cash change,
 * capture, void, and refund rules that ADR 0006 leans on for money-correctness.
 */
class PaymentTest {

  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID BIZ = UUID.randomUUID();
  private static final UUID SALE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  // ----------------------------------------------------------------- cash

  @Test
  void capturedCashStoresTenderedAndChangeAndIsCaptured() {
    Money amount = idr(83_000);
    Money tendered = idr(100_000);
    Money change = tendered.minus(amount); // 17_000

    Payment p = Payment.capturedCash(ORDER, BIZ, amount, tendered, change, SALE, NOW, "idem-1");

    assertThat(p.getStatus()).isEqualTo(Payment.Status.CAPTURED);
    assertThat(p.getTenderType()).isEqualTo(TenderType.CASH);
    assertThat(p.isProviderPending()).isFalse();
    assertThat(p.getTenderedMinor()).isEqualTo(100_000L);
    assertThat(p.getChangeMinor()).isEqualTo(17_000L);
    assertThat(p.getSaleId()).isEqualTo(SALE);
  }

  @Test
  void capturedCashRejectsNegativeChange() {
    Money amount = idr(83_000);
    Money tendered = idr(50_000);
    Money change = tendered.minus(amount); // -33_000

    assertThatThrownBy(
            () -> Payment.capturedCash(ORDER, BIZ, amount, tendered, change, SALE, NOW, "idem-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("change");
  }

  // -------------------------------------------------------------- digital

  @Test
  void pendingDigitalHasNoSaleUntilCaptured() {
    Payment p =
        Payment.pendingDigital(
            ORDER, BIZ, TenderType.QRIS, idr(83_000), "PENDING-X", NOW, "idem-3");

    assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING);
    assertThat(p.isProviderPending()).isTrue();
    assertThat(p.getSaleId()).isNull();

    p.capture(SALE, NOW);

    assertThat(p.getStatus()).isEqualTo(Payment.Status.CAPTURED);
    assertThat(p.getSaleId()).isEqualTo(SALE);
  }

  @Test
  void pendingDigitalRejectsCashTender() {
    assertThatThrownBy(
            () ->
                Payment.pendingDigital(ORDER, BIZ, TenderType.CASH, idr(1_000), "x", NOW, "idem-4"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void captureRejectedOnNonPending() {
    Payment p =
        Payment.capturedCash(ORDER, BIZ, idr(1_000), idr(1_000), idr(0), SALE, NOW, "idem-5");
    assertThatThrownBy(() -> p.capture(SALE, NOW)).isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------- void/refund

  @Test
  void voidTransitionsCapturedToVoided() {
    Payment p =
        Payment.capturedCash(ORDER, BIZ, idr(1_000), idr(1_000), idr(0), SALE, NOW, "idem-6");
    p.voidPayment();
    assertThat(p.getStatus()).isEqualTo(Payment.Status.VOIDED);
  }

  @Test
  void voidRejectedOnPending() {
    Payment p = Payment.pendingDigital(ORDER, BIZ, TenderType.CARD, idr(1_000), "x", NOW, "idem-7");
    assertThatThrownBy(p::voidPayment).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void partialThenFullRefundAccumulatesAndTransitions() {
    Payment p =
        Payment.capturedCash(ORDER, BIZ, idr(100_000), idr(100_000), idr(0), SALE, NOW, "idem-8");

    p.refund(idr(40_000));
    assertThat(p.getStatus()).isEqualTo(Payment.Status.PARTIALLY_REFUNDED);
    assertThat(p.getRefundedMinor()).isEqualTo(40_000L);

    p.refund(idr(60_000));
    assertThat(p.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    assertThat(p.getRefundedMinor()).isEqualTo(100_000L);
  }

  @Test
  void refundRejectedWhenExceedingRefundable() {
    Payment p =
        Payment.capturedCash(ORDER, BIZ, idr(100_000), idr(100_000), idr(0), SALE, NOW, "idem-9");
    p.refund(idr(70_000));
    assertThatThrownBy(() -> p.refund(idr(40_000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds");
  }
}
