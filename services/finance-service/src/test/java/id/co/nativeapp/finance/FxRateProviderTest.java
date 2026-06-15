package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.fx.domain.ResolvedFxRate;
import id.co.nativeapp.finance.fx.service.TableFxRateProvider;
import id.co.nativeapp.money.FxRate;
import id.co.nativeapp.money.Provenance;
import id.co.nativeapp.money.RateType;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test for {@link TableFxRateProvider} against a REAL PostgreSQL 16 (Testcontainers)
 * with the V4 migration applied. Proves:
 *
 * <ul>
 *   <li>the seeded STUB rates resolve, carrying {@code provenance = STUB} (so a converted figure
 *       can be flagged), and the exact scaled-integer pair round-trips;
 *   <li>CLOSING vs AVERAGE is selected by {@code rate_type} (the seed deliberately differs: 16,000
 *       vs 15,900);
 *   <li>EFFECTIVE-DATED, highest-version-wins resolution (an as-of date picks the right window /
 *       version), identical to {@code mapping_rule};
 *   <li><strong>RLS consistency</strong>: {@code fx_rate} is GLOBAL reference data — it resolves
 *       through the unprivileged, RLS-bound {@code app_user} datasource with NO tenant scope set
 *       (no {@code app.current_tenant} GUC), proving it is NOT under RLS (exactly like {@code
 *       chart_of_account} / {@code mapping_rule}).
 * </ul>
 */
@SpringBootTest
class FxRateProviderTest extends PostgresRlsTestBase {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency IDR = Currency.getInstance("IDR");
  private static final LocalDate AS_OF = LocalDate.parse("2026-06-15");

  @Autowired private TableFxRateProvider provider;

  @AfterEach
  void removeCustomRates() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      // Remove any test-inserted versioned rows; the V4 seed (version 1, far-past window) stays.
      st.execute("DELETE FROM fx_rate WHERE source_note LIKE 'TEST %'");
    }
  }

  @Test
  void resolvesTheSeededStubClosingRateExactlyWithProvenanceStub() {
    // The provider reads fx_rate with NO tenant scope bound (no app.current_tenant) — if fx_rate
    // were under RLS this would return empty. It does NOT, proving fx_rate is global (not RLS).
    Optional<ResolvedFxRate> resolved = provider.rateFor(USD, IDR, RateType.CLOSING, AS_OF);

    assertThat(resolved).isPresent();
    FxRate rate = resolved.get().rate();
    assertThat(rate.unscaled()).isEqualTo(16_000L);
    assertThat(rate.scale()).isEqualTo(0);
    assertThat(rate.from()).isEqualTo(USD);
    assertThat(rate.to()).isEqualTo(IDR);
    assertThat(rate.rateType()).isEqualTo(RateType.CLOSING);
    assertThat(rate.provenance()).isEqualTo(Provenance.STUB);
    assertThat(rate.isStub()).isTrue();
  }

  @Test
  void selectsClosingVsAverageByRateType() {
    // CLOSING USD->IDR = 16,000 ; AVERAGE = 15,900 (the seed deliberately differs).
    long closing =
        provider.rateFor(USD, IDR, RateType.CLOSING, AS_OF).orElseThrow().rate().unscaled();
    long average =
        provider.rateFor(USD, IDR, RateType.AVERAGE, AS_OF).orElseThrow().rate().unscaled();
    assertThat(closing).isEqualTo(16_000L);
    assertThat(average).isEqualTo(15_900L);
    assertThat(closing).isNotEqualTo(average);
  }

  @Test
  void resolvesTheExactInverseStubRate() {
    // 1 IDR = 0.0000625 USD (unscaled=625, scale=7) CLOSING.
    FxRate rate = provider.rateFor(IDR, USD, RateType.CLOSING, AS_OF).orElseThrow().rate();
    assertThat(rate.unscaled()).isEqualTo(625L);
    assertThat(rate.scale()).isEqualTo(7);
  }

  @Test
  void resolutionIsEffectiveDatedAndHighestVersionWins() throws Exception {
    // Insert a NEWER version of USD->IDR CLOSING effective from 2026-06-01 -> 17,000, version 2.
    // An as-of in May resolves to the seeded v1 (16,000, window from 2000); one in June to v2.
    seed(
        "INSERT INTO fx_rate (id, from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type,"
            + " provenance, source_note, version, effective_from, effective_to) VALUES"
            + " (gen_random_uuid(),'USD','IDR',17000,0,'CLOSING','LIVE','TEST v2',2,"
            + " DATE '2026-06-01', DATE '9999-12-31')");

    long may =
        provider
            .rateFor(USD, IDR, RateType.CLOSING, LocalDate.parse("2026-05-31"))
            .orElseThrow()
            .rate()
            .unscaled();
    long june =
        provider
            .rateFor(USD, IDR, RateType.CLOSING, LocalDate.parse("2026-06-01"))
            .orElseThrow()
            .rate()
            .unscaled();

    assertThat(may).isEqualTo(16_000L); // seeded v1 still in force in May
    assertThat(june).isEqualTo(17_000L); // v2 wins from June (highest version in-window)
  }

  @Test
  void twoOverlappingRowsAtTheSameVersionResolveDeterministicallyNewestEffectiveFromWins()
      throws Exception {
    // #36 determinism: two USD->IDR CLOSING rows that OVERLAP at the SAME version (2) — their
    // effective windows both contain the as-of date — would otherwise resolve ARBITRARILY (no
    // tie-break). The secondary sort `effective_from DESC` makes the NEWEST effective_from win.
    seed(
        "INSERT INTO fx_rate (id, from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type,"
            + " provenance, source_note, version, effective_from, effective_to) VALUES"
            + " (gen_random_uuid(),'USD','IDR',18000,0,'CLOSING','LIVE','TEST overlap-older',2,"
            + " DATE '2026-06-01', DATE '9999-12-31')");
    seed(
        "INSERT INTO fx_rate (id, from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type,"
            + " provenance, source_note, version, effective_from, effective_to) VALUES"
            + " (gen_random_uuid(),'USD','IDR',19000,0,'CLOSING','LIVE','TEST overlap-newer',2,"
            + " DATE '2026-06-10', DATE '9999-12-31')");

    // An as-of after BOTH effective_from dates: both rows are in-window at version 2. The newer
    // effective_from (2026-06-10 -> 19,000) must win deterministically every time.
    long resolved =
        provider
            .rateFor(USD, IDR, RateType.CLOSING, LocalDate.parse("2026-06-15"))
            .orElseThrow()
            .rate()
            .unscaled();
    assertThat(resolved).isEqualTo(19_000L);
  }

  @Test
  void aMissingDirectPairResolvesEmpty() {
    // EUR->IDR is not seeded directly; the provider answers only the single-pair question (empty).
    Optional<ResolvedFxRate> eurIdr =
        provider.rateFor(Currency.getInstance("EUR"), IDR, RateType.CLOSING, AS_OF);
    assertThat(eurIdr).isEmpty();
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
