package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.fx.MissingFxRateException;
import id.co.nativeapp.finance.fx.Translated;
import id.co.nativeapp.finance.fx.TranslationService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Currency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test for {@link TranslationService} against the seeded {@code fx_rate} (real
 * PostgreSQL). Proves the IAS-21 selection (AVERAGE for a flow, CLOSING for a balance), the exact
 * conversion to {@link Money} minor units, the {@code usesStubFx} flag from a STUB rate, USD-pivot
 * triangulation, the identity short-circuit, and the fail-loud missing-rate path.
 */
@SpringBootTest
class TranslationServiceTest extends PostgresRlsTestBase {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency IDR = Currency.getInstance("IDR");
  private static final Currency EUR = Currency.getInstance("EUR");

  @Autowired private TranslationService translationService;

  @AfterEach
  void removeCustomRates() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("DELETE FROM fx_rate WHERE source_note LIKE 'TEST %'");
    }
  }

  @Test
  void translateFlowUsesTheAverageRateAndFlagsStub() {
    // 1,590,000 IDR revenue (a FLOW) -> USD at AVERAGE (1 IDR = 0.0000629 USD, unscaled=629
    // scale=7).
    // 1,590,000 * 629 / 10^7 = 100.011 USD -> rounds to 100.01 (10001 minor) HALF_EVEN.
    Translated t = translationService.translateFlow(Money.ofMinor(1_590_000L, IDR), "2026-06", USD);

    assertThat(t.amount().currency()).isEqualTo(USD);
    assertThat(t.amount().amountMinor()).isEqualTo(10_001L);
    assertThat(t.usesStubFx()).isTrue(); // the seeded AVERAGE rate is a STUB
    assertThat(t.appliedRates()).hasSize(1);
    assertThat(t.appliedRates().getFirst().rateType().name()).isEqualTo("AVERAGE");
    assertThat(t.appliedRates().getFirst().provenance().name()).isEqualTo("STUB");
  }

  @Test
  void translateFlowUsdToIdrAtAverage() {
    // 100 USD (10_000 minor) -> IDR at AVERAGE 15,900: 10_000 * 15900 / 10^2 = 1,590,000 IDR.
    Translated t = translationService.translateFlow(Money.ofMinor(10_000L, USD), "2026-06", IDR);
    assertThat(t.amount()).isEqualTo(Money.ofMinor(1_590_000L, IDR));
    assertThat(t.usesStubFx()).isTrue();
  }

  @Test
  void translateBalanceUsesTheClosingRate() {
    // 100 USD balance -> IDR at CLOSING 16,000: 10_000 * 16000 / 10^2 = 1,600,000 IDR. Closing
    // (16,000) differs from average (15,900), proving the rate_type selection.
    Translated closing =
        translationService.translateBalance(Money.ofMinor(10_000L, USD), "2026-06", IDR);
    assertThat(closing.amount()).isEqualTo(Money.ofMinor(1_600_000L, IDR));

    Translated average =
        translationService.translateFlow(Money.ofMinor(10_000L, USD), "2026-06", IDR);
    assertThat(average.amount()).isEqualTo(Money.ofMinor(1_590_000L, IDR));
    assertThat(closing.amount()).isNotEqualTo(average.amount());
  }

  @Test
  void sameCurrencyShortCircuitsToIdentityWithNoStubFlag() {
    Money idr = Money.ofMinor(1_234_567L, IDR);
    Translated t = translationService.translateFlow(idr, "2026-06", IDR);
    assertThat(t.amount()).isEqualTo(idr); // unchanged
    assertThat(t.usesStubFx()).isFalse(); // identity is never stub-contaminated
  }

  @Test
  void triangulatesViaUsdWhenNoDirectPairExistsComposingAndRoundingOnce() throws Exception {
    // Seed EUR->USD AVERAGE = 1.10 (unscaled=110, scale=2) as a LIVE rate; no direct EUR->IDR.
    // The provider pivots: EUR->USD (1.10) then USD->IDR AVERAGE (15,900) composed = 17,490.
    // 100 EUR (10_000 minor) -> 10_000 * 110 * 15900 / (10^2 * 10^2 * 10^2... ) -> 1,749,000 IDR.
    seed(
        "INSERT INTO fx_rate (id, from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type,"
            + " provenance, source_note, version, effective_from, effective_to) VALUES"
            + " (gen_random_uuid(),'EUR','USD',110,2,'AVERAGE','LIVE','TEST eurusd',1,"
            + " DATE '2000-01-01', DATE '9999-12-31')");

    Translated t = translationService.translateFlow(Money.ofMinor(10_000L, EUR), "2026-06", IDR);

    assertThat(t.amount()).isEqualTo(Money.ofMinor(1_749_000L, IDR));
    // The USD->IDR leg is a STUB -> OR-ed contamination true even though EUR->USD is LIVE.
    assertThat(t.usesStubFx()).isTrue();
    // Two applied legs, both tagged with the USD pivot.
    assertThat(t.appliedRates()).hasSize(2);
    assertThat(t.appliedRates()).allMatch(r -> "USD".equals(r.pivotCcy()));
  }

  @Test
  void aMissingRateFailsLoudly() {
    // No EUR->IDR direct AND no EUR->USD leg seeded -> neither direct nor pivot resolves.
    assertThatThrownBy(
            () -> translationService.translateFlow(Money.ofMinor(100L, EUR), "2026-06", IDR))
        .isInstanceOf(MissingFxRateException.class);
  }

  private void seed(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(sql);
    }
  }
}
