package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests (no Spring, no DB) for the Phase 4 (ADR 0027) extensions to {@link
 * SaleRecordedEvent}:
 *
 * <ul>
 *   <li>the EXTENDED reconciliation identity — {@code subtotal − discount − loyaltyRedeemed +
 *       serviceCharge + tax == amount} — including the new loyalty term;
 *   <li>the NEW gift-card TENDER guard — {@code gift_card_redeemed_minor}, when present, must be
 *       {@code > 0} and {@code <= amount_minor};
 *   <li>the regression-critical guarantee that a pre-Phase-4 event (all five Phase 4 fields null)
 *       evaluates byte-identically to before Phase 4 (no throw, same identity as Phase 2).
 * </ul>
 *
 * <p>Both checks funnel into the SAME {@code IllegalStateException} that {@code
 * RevenuePostingWriter#postRevenue} already lets propagate out of the {@code @Transactional} unit
 * of work (rolling it back) and out of the {@code @KafkaListener} method, where the container's
 * bounded-retry-then-DLT error handler routes the poison record to {@code SaleRecorded.DLT} — the
 * exact mechanism {@code SaleRecordedDltTest}/{@code SaleRecordedMissingHeaderDltTest} already
 * prove generically. These tests prove the NEW guards trigger that same poison path; no Kafka
 * container is needed to prove the guard logic itself (mirrors {@code PostingCurrencyGuardTest}'s
 * posture).
 */
class Phase4ReconciliationGuardTest {

  private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID SALE_ID = UUID.randomUUID();
  private static final String TENANT = "tenant-phase4";

  // -----------------------------------------------------------------------
  // Extended reconciliation identity: loyalty redemption
  // -----------------------------------------------------------------------

  @Test
  void extendedIdentityHoldsWithLoyaltyRedemption() {
    // subtotal=30_000, discount=0, loyaltyRedeemed=5_000, SC=0, tax=0 -> amount=25_000
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 25_000L, null, null, 5_000L, null, null);

    assertThatCode(event::assertReconciliationIdentity).doesNotThrowAnyException();
  }

  @Test
  void extendedIdentityViolatedByWrongLoyaltyRedeemedThrows() {
    // subtotal=30_000, discount=0, loyaltyRedeemed=5_000 but amount is wrongly 30_000 (producer
    // forgot to deduct the redemption from the grand total) -> violated identity.
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, 5_000L, null, null);

    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reconciliation identity violated")
        .hasMessageContaining("loyaltyRedeemed");
  }

  @Test
  void extendedIdentityHoldsWithLoyaltyAndDiscountAndServiceChargeAndTaxCombined() {
    // subtotal=50_000, discount=2_000, loyaltyRedeemed=3_000, SC=2_250(5% of 45_000),
    // tax=4_725(10% of 47_250) -> amount = 45_000 + 2_250 + 4_725 = 51_975
    SaleRecordedEvent event =
        phase4Event(50_000L, 2_000L, 2_250L, 4_725L, 51_975L, null, null, 3_000L, null, null);

    assertThatCode(event::assertReconciliationIdentity).doesNotThrowAnyException();
  }

  // -----------------------------------------------------------------------
  // Gift-card tender guard
  // -----------------------------------------------------------------------

  @Test
  void giftCardRedeemedWithinBoundsDoesNotThrow() {
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, null, null, 10_000L);

    assertThatCode(event::assertReconciliationIdentity).doesNotThrowAnyException();
  }

  @Test
  void giftCardRedeemedEqualToAmountDoesNotThrow() {
    // Fully gift-card-paid: gift_card_redeemed_minor == amount_minor is the boundary (<=), valid.
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, null, null, 30_000L);

    assertThatCode(event::assertReconciliationIdentity).doesNotThrowAnyException();
  }

  @Test
  void giftCardRedeemedZeroThrows() {
    SaleRecordedEvent event = phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, null, null, 0L);

    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gift_card_redeemed_minor must be > 0");
  }

  @Test
  void giftCardRedeemedNegativeThrows() {
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, null, null, -1L);

    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gift_card_redeemed_minor must be > 0");
  }

  @Test
  void giftCardRedeemedExceedingAmountThrows() {
    SaleRecordedEvent event =
        phase4Event(30_000L, 0L, 0L, 0L, 30_000L, null, null, null, null, 30_001L);

    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be <= amount_minor");
  }

  @Test
  void giftCardGuardRunsEvenWithoutABreakdown() {
    // No Phase 2 breakdown (subtotal null) but a gift-card redemption is present and invalid --
    // the guard must still fire (it does not depend on the breakdown fields being present).
    SaleRecordedEvent legacyShapedWithBadGiftCard =
        new SaleRecordedEvent(
            EVENT_ID,
            SALE_ID,
            TENANT,
            UUID.randomUUID(),
            Money.ofMinor(10_000L, "IDR"),
            NOW,
            "CASH",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            UUID.randomUUID().toString(),
            20_000L, // exceeds amount_minor(10_000)
            null); // channel (Phase B, ADR 0036) — not exercised by this test

    assertThatThrownBy(legacyShapedWithBadGiftCard::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be <= amount_minor");
  }

  // -----------------------------------------------------------------------
  // Regression: a pre-Phase-4 event (all five fields null) is byte-identical to before Phase 4
  // -----------------------------------------------------------------------

  @Test
  void prePhase4EventWithAllNullPhase4FieldsNeverThrows() {
    // Phase 2 event via the pre-Phase-4 13-arg backward-compatible constructor.
    SaleRecordedEvent event =
        new SaleRecordedEvent(
            EVENT_ID,
            SALE_ID,
            TENANT,
            UUID.randomUUID(),
            Money.ofMinor(34_650L, "IDR"),
            NOW,
            "CASH",
            30_000L,
            0L,
            1_500L,
            3_150L,
            "v1-illustrative",
            true);

    assertThatCode(event::assertReconciliationIdentity).doesNotThrowAnyException();
    assertThat(event.effectiveLoyaltyRedeemed()).isEqualTo(Money.ofMinor(0L, "IDR"));
    assertThat(event.effectiveGiftCardRedeemed()).isEqualTo(Money.ofMinor(0L, "IDR"));
  }

  @Test
  void legacyEventFromTheFiveArgConstructorNeverThrowsAndDefaultsAreZero() {
    SaleRecordedEvent legacyEvent =
        new SaleRecordedEvent(
            EVENT_ID, TENANT, UUID.randomUUID(), Money.ofMinor(20_000L, "IDR"), NOW);

    assertThatCode(legacyEvent::assertReconciliationIdentity).doesNotThrowAnyException();
    assertThat(legacyEvent.effectiveLoyaltyRedeemed().isZero()).isTrue();
    assertThat(legacyEvent.effectiveGiftCardRedeemed().isZero()).isTrue();
  }

  // -----------------------------------------------------------------------
  private SaleRecordedEvent phase4Event(
      long subtotal,
      long discount,
      long sc,
      long tax,
      long grand,
      String loyaltyMemberId,
      Long loyaltyRedeemedPoints,
      Long loyaltyRedeemedMinor,
      String giftCardId,
      Long giftCardRedeemedMinor) {
    return new SaleRecordedEvent(
        EVENT_ID,
        SALE_ID,
        TENANT,
        UUID.randomUUID(),
        Money.ofMinor(grand, "IDR"),
        NOW,
        "CASH",
        subtotal,
        discount,
        sc,
        tax,
        "v1-illustrative",
        true,
        loyaltyMemberId,
        loyaltyRedeemedPoints,
        loyaltyRedeemedMinor,
        giftCardId,
        giftCardRedeemedMinor,
        null); // channel (Phase B, ADR 0036) — not exercised by this test
  }
}
