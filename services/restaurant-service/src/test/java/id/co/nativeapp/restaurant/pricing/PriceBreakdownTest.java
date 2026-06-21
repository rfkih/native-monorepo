package id.co.nativeapp.restaurant.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link PriceBreakdown} — the pricing value object (B2 coverage).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Reconciliation identity: {@code subtotal − discount + SC + tax == grandTotal}.
 *   <li>Discount clamp: discount is clamped to ≤ subtotal.
 *   <li>No-rule fallthrough: tax == 0, SC == 0 → grand == subtotal.
 *   <li>Non-zero discount case.
 *   <li>Violation of the reconciliation identity throws {@link IllegalArgumentException}.
 * </ul>
 *
 * <p>No Spring context or database needed.
 */
class PriceBreakdownTest {

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  // -----------------------------------------------------------------------
  // 1. Well-formed breakdown (subtotal=30k, discount=0, SC=1.5k, tax=3.15k, grand=34.65k)
  // -----------------------------------------------------------------------

  @Test
  void validBreakdownConstructsSuccessfully() {
    PriceBreakdown b =
        new PriceBreakdown(
            idr(30_000L), // subtotal
            idr(0L), // discount
            idr(30_000L), // taxableBase = subtotal − discount
            idr(1_500L), // serviceCharge
            idr(3_150L), // tax
            idr(34_650L), // grandTotal = 30_000 + 1_500 + 3_150
            "v1-illustrative",
            true);

    assertThat(b.subtotal().amountMinor()).isEqualTo(30_000L);
    assertThat(b.discount().amountMinor()).isZero();
    assertThat(b.taxableBase().amountMinor()).isEqualTo(30_000L);
    assertThat(b.serviceCharge().amountMinor()).isEqualTo(1_500L);
    assertThat(b.tax().amountMinor()).isEqualTo(3_150L);
    assertThat(b.grandTotal().amountMinor()).isEqualTo(34_650L);
    assertThat(b.taxRuleVersion()).isEqualTo("v1-illustrative");
    assertThat(b.usesIllustrativeRules()).isTrue();
  }

  @Test
  void reconciliationIdentityIsAssertedOnConstruction() {
    // subtotal − discount + SC + tax == grandTotal MUST hold.
    // Here we intentionally break it: grand should be 34,650 but we pass 35,000.
    assertThatThrownBy(
            () ->
                new PriceBreakdown(
                    idr(30_000L),
                    idr(0L),
                    idr(30_000L),
                    idr(1_500L),
                    idr(3_150L),
                    idr(35_000L), // wrong grand total
                    "v1",
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reconciliation");
  }

  // -----------------------------------------------------------------------
  // 2. Non-zero discount case (subtotal=30k, discount=5k, grand=28.875k)
  // -----------------------------------------------------------------------

  @Test
  void breakdownWithNonZeroDiscountIsValid() {
    // subtotal=30k, disc=5k, taxableBase=25k, SC=1.25k(5%), taxBase=26.25k, tax=2.625k(10%)
    // grand = 25k + 1.25k + 2.625k = 28.875k
    PriceBreakdown b =
        new PriceBreakdown(
            idr(30_000L), // subtotal
            idr(5_000L), // discount
            idr(25_000L), // taxableBase = 30_000 − 5_000
            idr(1_250L), // serviceCharge = 25_000 * 5%
            idr(2_625L), // tax = 26_250 * 10%
            idr(28_875L), // grand = 25_000 + 1_250 + 2_625
            "v1-illustrative",
            true);

    Money net = b.subtotal().minus(b.discount());
    assertThat(net.amountMinor())
        .as("net revenue (subtotal − discount) = 25,000")
        .isEqualTo(25_000L);
    // net != grand (discount case): the read model must accumulate NET, not grand.
    assertThat(net.amountMinor())
        .as("net must differ from grand total when discount > 0")
        .isNotEqualTo(b.grandTotal().amountMinor());
  }

  // -----------------------------------------------------------------------
  // 3. Discount clamp: discount ≤ subtotal always
  // -----------------------------------------------------------------------

  @Test
  void discountClampedToSubtotal_zeroNetIsPossible() {
    // subtotal=10k, discount=10k (clamped), taxableBase=0, SC=0, tax=0, grand=0.
    PriceBreakdown b =
        new PriceBreakdown(
            idr(10_000L), // subtotal
            idr(10_000L), // discount (clamped to subtotal)
            idr(0L), // taxableBase = 0
            idr(0L), // serviceCharge
            idr(0L), // tax
            idr(0L), // grand = 0
            null,
            false);

    assertThat(b.grandTotal().amountMinor()).isZero();
    assertThat(b.discount().amountMinor()).isEqualTo(b.subtotal().amountMinor());
  }

  @Test
  void discountExceedingSubtotalViolatesReconciliation() {
    // If discount > subtotal, taxableBase = subtotal − discount would be negative.
    // The correct behaviour is to clamp at the pricing layer (TaxChargeService).
    // A PriceBreakdown constructed with discount > subtotal and wrong taxableBase
    // will fail the identity check.
    assertThatThrownBy(
            () ->
                new PriceBreakdown(
                    idr(10_000L), // subtotal
                    idr(15_000L), // discount > subtotal (should have been clamped)
                    idr(-5_000L), // taxableBase (negative — invalid)
                    idr(0L),
                    idr(0L),
                    idr(0L), // grand = taxableBase + SC + tax = -5_000 ≠ 0 constraint violated
                    null,
                    false))
        .isInstanceOf(Exception.class); // IllegalArgumentException or Money constraint
  }

  // -----------------------------------------------------------------------
  // 4. No-rule fallthrough: tax=0, SC=0
  // -----------------------------------------------------------------------

  @Test
  void noRuleFallthroughZeroTaxAndSC() {
    // When no rules are seeded, TaxChargeService returns tax=0, SC=0.
    // grand = subtotal − discount + 0 + 0 = subtotal.
    PriceBreakdown b =
        new PriceBreakdown(
            idr(20_000L), // subtotal
            idr(0L), // discount
            idr(20_000L), // taxableBase
            idr(0L), // serviceCharge (no rule)
            idr(0L), // tax (no rule)
            idr(20_000L), // grand == subtotal
            null, // no taxRuleVersion (no rule resolved)
            false);

    assertThat(b.tax().isZero()).isTrue();
    assertThat(b.serviceCharge().isZero()).isTrue();
    assertThat(b.grandTotal()).isEqualTo(b.subtotal());
    assertThat(b.taxRuleVersion()).isNull();
    assertThat(b.usesIllustrativeRules()).isFalse();
  }
}
