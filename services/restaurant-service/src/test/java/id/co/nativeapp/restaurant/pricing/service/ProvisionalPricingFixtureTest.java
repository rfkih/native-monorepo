package id.co.nativeapp.restaurant.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Plain JUnit test (no Spring context, no DB) that loads the client/server SHARED pricing-parity
 * fixture and asserts {@link PricingFormula#compute} — the exact formula {@link
 * TaxChargeService#resolve} calls — reproduces every case's expected breakdown.
 *
 * <p>This is the drift detector for Phase 5 offline mode (ADR 0028): the fixture at {@code
 * frontend/console/src/features/pos/offline/pricing-parity.fixture.json} is committed ONCE and
 * asserted by BOTH this server-side test and the client's {@code provisionalPricing.test.ts} vitest
 * suite. If a change to {@link PricingFormula} breaks THIS test, the client's provisional
 * (offline-computed) pricing has silently diverged from the server's authoritative repricing —
 * regenerate the fixture (and confirm the client suite still passes) before merging.
 *
 * <p><strong>Relative path.</strong> Gradle's {@code test} task working directory is the module
 * directory ({@code services/restaurant-service}), so {@code ../../frontend/...} climbs up TWO
 * levels to the repo root ({@code services/restaurant-service -> services -> <repo root>}) and back
 * down into {@code frontend/console/...} — see {@link #FIXTURE_PATH}.
 */
class ProvisionalPricingFixtureTest {

  private static final String FIXTURE_PATH =
      "../../frontend/console/src/features/pos/offline/pricing-parity.fixture.json";

  @TestFactory
  Stream<DynamicTest> everyFixtureCaseReproducesTheServerBreakdown() throws IOException {
    List<FixtureCase> cases = loadFixture();
    assertThat(cases).as("fixture must not be empty").isNotEmpty();
    return cases.stream()
        .map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> assertCase(testCase)));
  }

  private static void assertCase(FixtureCase testCase) {
    String currency = testCase.rules().currency();
    Money subtotal = Money.ofMinor(testCase.subtotalMinor(), currency);
    Money fixedDiscount =
        testCase.fixedDiscountMinor() != null
            ? Money.ofMinor(testCase.fixedDiscountMinor(), currency)
            : null;
    long discountBp = testCase.discountBp() != null ? testCase.discountBp() : 0L;

    PriceBreakdown breakdown =
        PricingFormula.compute(
            subtotal,
            discountBp,
            fixedDiscount,
            testCase.rules().serviceChargeBp(),
            testCase.rules().serviceChargeInTaxBase(),
            testCase.rules().taxBp(),
            testCase.rules().taxRuleVersion(),
            false);

    ExpectedBlock expected = testCase.expected();
    assertThat(breakdown.subtotal().amountMinor()).isEqualTo(expected.subtotalMinor());
    assertThat(breakdown.discount().amountMinor()).isEqualTo(expected.discountMinor());
    assertThat(breakdown.serviceCharge().amountMinor()).isEqualTo(expected.serviceChargeMinor());
    assertThat(breakdown.tax().amountMinor()).isEqualTo(expected.taxMinor());
    assertThat(breakdown.grandTotal().amountMinor()).isEqualTo(expected.grandTotalMinor());
    assertThat(breakdown.grandTotal().currency().getCurrencyCode()).isEqualTo(expected.currency());
  }

  private static List<FixtureCase> loadFixture() throws IOException {
    File file = new File(FIXTURE_PATH);
    assertThat(file)
        .as(
            "Shared pricing-parity fixture not found at %s (test cwd=%s) — see class javadoc for"
                + " the relative-path assumption",
            file.getAbsolutePath(),
            new File(".").getAbsolutePath())
        .exists();
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(
        file, mapper.getTypeFactory().constructCollectionType(List.class, FixtureCase.class));
  }

  /** One fixture case: an offline-pricing input plus its expected server-computed breakdown. */
  private record FixtureCase(
      String name,
      long subtotalMinor,
      RulesBlock rules,
      Long discountBp,
      Long fixedDiscountMinor,
      ExpectedBlock expected) {}

  /** Mirrors the wire shape of {@code GET /api/v1/pricing/effective-rules}. */
  private record RulesBlock(
      String currency,
      String asOf,
      long taxBp,
      String taxRuleVersion,
      String taxProvenance,
      long serviceChargeBp,
      boolean serviceChargeInTaxBase,
      String serviceChargeRuleVersion,
      String serviceChargeProvenance) {}

  private record ExpectedBlock(
      long subtotalMinor,
      long discountMinor,
      long serviceChargeMinor,
      long taxMinor,
      long grandTotalMinor,
      String currency) {}
}
