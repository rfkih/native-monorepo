package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.wash.domain.Wash;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain test for the {@link Wash} aggregate (no Spring): construction validation, the
 * optional upsell, and the same-currency invariant — money is {@link Money}, never a float (rule
 * 8).
 */
class WashDomainTest {

  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant WHEN = Instant.parse("2026-06-14T08:30:00Z");

  @Test
  void aWashWithoutAnUpsellHasNoUpsellAndZeroUpsellAmount() {
    Wash wash =
        new Wash(OUTLET, "bay-1", Optional.empty(), Money.ofMinor(5_000_00L, "IDR"), WHEN, "k1");
    assertThat(wash.hasUpsell()).isFalse();
    assertThat(wash.getUpsellAmountMinor()).isZero();
    assertThat(wash.getAmount().amountMinor()).isEqualTo(5_000_00L);
    assertThat(wash.getAmount().currency().getCurrencyCode()).isEqualTo("IDR");
  }

  @Test
  void aWashWithAnUpsellCarriesItsNameAndAmount() {
    Wash wash =
        new Wash(
            OUTLET,
            "bay-2",
            Optional.of(new Wash.Upsell("wax", Money.ofMinor(2_500_00L, "IDR"))),
            Money.ofMinor(7_500_00L, "IDR"),
            WHEN,
            "k2");
    assertThat(wash.hasUpsell()).isTrue();
    assertThat(wash.getUpsellName()).isEqualTo("wax");
    assertThat(wash.getUpsellAmountMinor()).isEqualTo(2_500_00L);
  }

  @Test
  void anUpsellInADifferentCurrencyThanTheWashIsRejected() {
    assertThatThrownBy(
            () ->
                new Wash(
                    OUTLET,
                    "bay-3",
                    Optional.of(new Wash.Upsell("wax", Money.ofMinor(100L, "USD"))),
                    Money.ofMinor(7_500_00L, "IDR"),
                    WHEN,
                    "k3"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void aBlankBayIsRejected() {
    assertThatThrownBy(
            () -> new Wash(OUTLET, " ", Optional.empty(), Money.ofMinor(1L, "IDR"), WHEN, "k4"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
