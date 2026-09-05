package id.co.nativeapp.restaurant.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.integrity.service.OperatorOutliers;
import id.co.nativeapp.restaurant.integrity.service.OperatorOutliers.OperatorRate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for {@link OperatorOutliers} — the arithmetic that decides whose NAME appears in front
 * of an owner on a page about theft.
 *
 * <p>Most of these tests are about restraint rather than detection. A rate comparison that fires
 * too easily does not crash or look wrong; it quietly accuses somebody, which is the one failure
 * this feature must not have. So the guards get more coverage here than the happy path does.
 */
class OperatorOutliersTest {

  private static final long FACTOR = 2L;
  private static final long MIN_COUNT = 5L;

  @Test
  void anOperatorVoidingFarMoreThanTheirColleaguesIsFlagged() {
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 20, 100, 400_000L), // 20%
            new OperatorRate("sari", 2, 100, 40_000L), //  2%
            new OperatorRate("dewi", 3, 100, 60_000L)); //  3%

    List<OperatorRate> flagged = OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT);

    // Budi's 20% against the others' combined 5/200 = 2.5% — eight times, well past the bar.
    assertThat(flagged).extracting(OperatorRate::actor).containsExactly("budi");
  }

  @Test
  void theBaselineExcludesTheOperatorBeingJudged() {
    // The case that makes self-exclusion load-bearing rather than cosmetic. Budi voids 30% against
    // Sari's 10%. Measured against the OUTLET-WIDE rate (40/200 = 20%) Budi is only 1.5x and slips
    // through; measured against Sari alone he is 3x and is caught. The fewer people on the roster,
    // the more an outlier drags the average toward themselves -- and small rosters are exactly
    // where this feature is used.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 30, 100, 600_000L), //
            new OperatorRate("sari", 10, 100, 200_000L));

    List<OperatorRate> flagged = OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT);

    assertThat(flagged).extracting(OperatorRate::actor).containsExactly("budi");
  }

  @Test
  void whenMostOfTheRosterBehavesTheSameWayNobodyIsAnOutlier() {
    // Two of three operators void at 20%. Each one's baseline contains the other, so neither
    // reaches the factor -- and that is the right answer: when most of the outlet does something,
    // it is the outlet's practice, not one person's anomaly. Reporting both here would turn a
    // question about training or a broken workflow into an accusation against two people.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 20, 100, 400_000L),
            new OperatorRate("sari", 20, 100, 400_000L),
            new OperatorRate("dewi", 2, 100, 40_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void aLoneOperatorIsNeverAnOutlier() {
    // With nobody else on the roster there is no "unlike everyone else" to measure. An owner who
    // works their own till alone must not be reported as an anomaly against themselves.
    List<OperatorRate> rates = List.of(new OperatorRate("owner", 50, 60, 1_000_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void aTinySampleIsIgnoredHoweverExtremeItsRate() {
    // 3 voids out of 3 sales is a 100% rate and evidence of nothing. Without the floor, the
    // quietest person on the roster tops every list by arithmetic alone.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("baru", 3, 3, 60_000L), //
            new OperatorRate("lama", 4, 400, 80_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void anOperatorWithNoOpportunityHasNoRate() {
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("nol", 9, 0, 0L), // refunded old sales, took none this window
            new OperatorRate("aktif", 1, 100, 20_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void anOperatorIsFlaggedWhenNobodyElseDidItAtAll() {
    // The others' rate is exactly zero, so any sustained activity is infinitely above it. This is
    // the case the cross-multiplied comparison has to get right without dividing by zero.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 6, 100, 120_000L), //
            new OperatorRate("sari", 0, 100, 0L));

    List<OperatorRate> flagged = OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT);

    assertThat(flagged).extracting(OperatorRate::actor).containsExactly("budi");
  }

  @Test
  void beingMerelyAboveAverageIsNotEnough() {
    // 6% against 5% is a real difference and a terrible reason to name somebody. The factor exists
    // so ordinary variation between shifts never reaches an owner as a finding.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 6, 100, 120_000L), //
            new OperatorRate("sari", 5, 100, 100_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void theBoundaryIsInclusiveSoExactlyTwiceCounts() {
    // budi 10/100 = 10%; sari 5/100 = 5%. Exactly the factor, and the comparison is >=.
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("budi", 10, 100, 200_000L), //
            new OperatorRate("sari", 5, 100, 100_000L));

    assertThat(OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT))
        .extracting(OperatorRate::actor)
        .containsExactly("budi");
  }

  @Test
  void flaggedOperatorsAreOrderedByTheMoneyInvolvedThenDeterministically() {
    List<OperatorRate> rates =
        List.of(
            new OperatorRate("kecil", 10, 100, 50_000L),
            new OperatorRate("besar", 10, 100, 900_000L),
            new OperatorRate("sama", 10, 100, 50_000L),
            new OperatorRate("bersih", 0, 400, 0L));

    List<OperatorRate> flagged = OperatorOutliers.outliers(rates, FACTOR, MIN_COUNT);

    // Largest amount first; ties broken by actor so two runs of the same report never disagree.
    assertThat(flagged).extracting(OperatorRate::actor).containsExactly("besar", "kecil", "sama");
  }

  @Test
  void anEmptyRosterProducesNothing() {
    assertThat(OperatorOutliers.outliers(List.of(), FACTOR, MIN_COUNT)).isEmpty();
  }

  @Test
  void totalValueSumsTheMoneyBehindTheFlaggedOperators() {
    assertThat(
            OperatorOutliers.totalValue(
                List.of(
                    new OperatorRate("a", 1, 2, 30_000L), new OperatorRate("b", 1, 2, 12_500L))))
        .isEqualTo(42_500L);
    assertThat(OperatorOutliers.totalValue(List.of())).isZero();
  }
}
