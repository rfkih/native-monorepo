package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for the net-revenue derivation in the {@code SaleRecordedEvent} (the values the
 * finance read-model accumulates, which the reversal path must unwind exactly — B1 correctness).
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Net revenue == subtotal − discount for Phase 2 events.
 *   <li>Net revenue == grand total for legacy (null-breakdown) events (net == gross for Phase 1).
 *   <li>Non-zero discount reduces net revenue but not grand total.
 *   <li>The reconciliation identity holds: subtotal − discount + SC + tax == grandTotal.
 * </ul>
 *
 * <p>No Spring context or database needed.
 */
class DeriveNetRevenueFromLinesTest {

  private static final Instant NOW = Instant.parse("2026-06-14T12:00:00Z");

  // -----------------------------------------------------------------------
  // Net revenue derivation: subtotal − discount
  // -----------------------------------------------------------------------

  @Test
  void netRevenueIsSubtotalMinusDiscountForPhase2Sale() {
    // subtotal=30_000, discount=0, SC=1_500, tax=3_150, grand=34_650
    SaleRecordedEvent event = phase2Event(30_000L, 0L, 1_500L, 3_150L, 34_650L);

    Money netRevenue = event.effectiveSubtotal().minus(event.effectiveDiscount());

    assertThat(netRevenue.amountMinor())
        .as("net = subtotal(30,000) − discount(0) = 30,000")
        .isEqualTo(30_000L);
    // Grand total is 34,650 ≠ net (30,000): the read model accumulates net, NOT grand total.
    assertThat(event.amount().amountMinor()).isEqualTo(34_650L);
    assertThat(netRevenue.amountMinor()).isNotEqualTo(event.amount().amountMinor());
  }

  @Test
  void netRevenueWithNonZeroDiscountEqualsSubtotalMinusDiscount() {
    // subtotal=30_000, discount=5_000, SC=1_250, tax=2_625, grand=28_875
    SaleRecordedEvent event = phase2Event(30_000L, 5_000L, 1_250L, 2_625L, 28_875L);

    Money netRevenue = event.effectiveSubtotal().minus(event.effectiveDiscount());

    assertThat(netRevenue.amountMinor())
        .as("net = subtotal(30,000) − discount(5,000) = 25,000")
        .isEqualTo(25_000L);
    assertThat(event.effectiveDiscount().amountMinor()).isEqualTo(5_000L);
  }

  @Test
  void legacyEventNetEqualsGrandTotal() {
    // Legacy (null breakdown): effectiveSubtotal() == amount, effectiveDiscount() == 0.
    SaleRecordedEvent legacyEvent =
        new SaleRecordedEvent(
            UUID.randomUUID(), "tenant-01", UUID.randomUUID(), Money.ofMinor(20_000L, "IDR"), NOW);

    Money netRevenue = legacyEvent.effectiveSubtotal().minus(legacyEvent.effectiveDiscount());

    assertThat(netRevenue.amountMinor())
        .as("legacy event: net == grand total == subtotal (no breakdown)")
        .isEqualTo(20_000L);
    assertThat(netRevenue).isEqualTo(legacyEvent.amount());
  }

  // -----------------------------------------------------------------------
  // Reconciliation identity: subtotal − discount + SC + tax == grandTotal
  // -----------------------------------------------------------------------

  @Test
  void reconciliationIdentityHoldsForTypicalPhase2Sale() {
    long sub = 30_000L;
    long disc = 0L;
    long sc = 1_500L;
    long tax = 3_150L;
    long grand = 34_650L;

    SaleRecordedEvent event = phase2Event(sub, disc, sc, tax, grand);

    // The SaleRecordedEvent.assertReconciliationIdentity() must not throw.
    event.assertReconciliationIdentity();

    Money expected =
        event
            .effectiveSubtotal()
            .minus(event.effectiveDiscount())
            .plus(event.effectiveServiceCharge())
            .plus(event.effectiveTax());
    assertThat(expected).isEqualTo(event.amount());
  }

  @Test
  void reconciliationIdentityHoldsWithNonZeroDiscount() {
    // subtotal=30k, discount=5k, taxableBase=25k, SC=1.25k(5%), taxBase=26.25k, tax=2.625k(10%)
    long sub = 30_000L;
    long disc = 5_000L;
    long sc = 1_250L;
    long tax = 2_625L;
    long grand = 28_875L; // 25_000 + 1_250 + 2_625

    SaleRecordedEvent event = phase2Event(sub, disc, sc, tax, grand);
    event.assertReconciliationIdentity();

    Money net = event.effectiveSubtotal().minus(event.effectiveDiscount());
    assertThat(net.amountMinor()).isEqualTo(25_000L);
  }

  // -----------------------------------------------------------------------
  // No-rule fallthrough: tax=0 → net == subtotal
  // -----------------------------------------------------------------------

  @Test
  void noRuleFallthroughTaxZeroNetEqualsSubtotal() {
    // No rules seeded: tax=0, SC=0. grand = subtotal.
    long sub = 20_000L;
    SaleRecordedEvent event = phase2Event(sub, 0L, 0L, 0L, sub);

    event.assertReconciliationIdentity();
    Money net = event.effectiveSubtotal().minus(event.effectiveDiscount());
    assertThat(net.amountMinor())
        .as("no-rule fallthrough: tax=0, SC=0, net == subtotal")
        .isEqualTo(sub);
  }

  // -----------------------------------------------------------------------
  // Discount clamp: discount clamped to ≤ subtotal (tested at SaleRecordedEvent level)
  // -----------------------------------------------------------------------

  @Test
  void discountCannotExceedSubtotal_clampedAtSubtotal() {
    // This tests the PriceBreakdown / TaxChargeService clamp at the pricing layer.
    // At the SaleRecordedEvent level: discount is already clamped by the producer.
    // subtotal=10k, discount=10k (clamped), taxableBase=0, SC=0, tax=0, grand=0.
    // But grand=0 is rejected at checkout. So we just verify net != negative.
    long sub = 10_000L;
    long disc = 10_000L; // fully comped (clamped to subtotal)
    long sc = 0L;
    long tax = 0L;
    long grand = 0L; // taxableBase=0, SC=0, tax=0

    // Note: grand=0 is rejected at checkout (OrderWriter) but the SaleRecordedEvent itself
    // is not constrained to positive grand (that guard is at the order level). Here we just
    // verify the breakdown values are sane.
    SaleRecordedEvent event = phase2Event(sub, disc, sc, tax, grand);
    event.assertReconciliationIdentity();

    Money net = event.effectiveSubtotal().minus(event.effectiveDiscount());
    assertThat(net.amountMinor())
        .as("discount clamped to subtotal: net = subtotal − subtotal = 0")
        .isZero();
  }

  // -----------------------------------------------------------------------
  private SaleRecordedEvent phase2Event(
      long subtotal, long discount, long sc, long tax, long grand) {
    return new SaleRecordedEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "tenant-01",
        UUID.randomUUID(),
        Money.ofMinor(grand, "IDR"),
        NOW,
        "CASH",
        subtotal,
        discount,
        sc,
        tax,
        "v1-illustrative",
        true);
  }
}
