package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams.HourlyRateTableParams;
import id.co.nativeapp.employee.payroll.service.WorkInputCalculator;
import id.co.nativeapp.money.Money;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regulation-cited unit tests for the pure {@link WorkInputCalculator} (Track P Phase P7) — no
 * Spring/DB, mirrors {@code GrossToNetOfficialDatasetTest}/{@code LaborCostAllocatorTest}'s "unit
 * context" precedent. Loads the SAME {@code OVERTIME_HOURLY} params shipped in {@code ID-2026.1}/
 * {@code ID-2026.2} (PP 35/2021 j.o. Kepmenaker 102/2004), never hand-rolled figures.
 */
class WorkInputCalculatorTest {

  private static final String IDR = "IDR";
  private final WorkInputCalculator calculator = new WorkInputCalculator();

  /**
   * monthly_divisor 173, weekday [1@1.5x, open@2x], rest_day [7@2x, 1@3x, open@4x] — PP 35/2021.
   */
  private static final HourlyRateTableParams OVERTIME_PARAMS =
      new HourlyRateTableParams(
          173,
          Map.of(
              "weekday",
              java.util.List.of(
                  new StatutoryParams.HourlyTier(1, 15000),
                  new StatutoryParams.HourlyTier(null, 20000)),
              "rest_day",
              java.util.List.of(
                  new StatutoryParams.HourlyTier(7, 20000),
                  new StatutoryParams.HourlyTier(1, 30000),
                  new StatutoryParams.HourlyTier(null, 40000))));

  // ---------------------------------------------------------------------
  // Overtime — PP 35/2021 worked example: 3h weekday = 1.5x hour 1 + 2x hours 2-3
  // ---------------------------------------------------------------------

  @Test
  void threeHoursWeekdayOvertimeIsOneAndAHalfTimesHourOnePlusTwoTimesTheNextTwoHours() {
    // base 17,300,000 IDR -> hourly = 17,300,000 / 173 = 100,000 exactly (a clean worked figure).
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 180, 0);
    // hour 1: 100,000 * 1.5 = 150,000; hours 2-3 (120 min): 100,000 * 2.0 * 2h = 400,000.
    assertThat(pay).isEqualTo(Money.ofMinor(550_000L, IDR));
  }

  @Test
  void oneHourWeekdayOvertimeIsExactlyOneAndAHalfTimesTheHourlyRate() {
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 60, 0);
    assertThat(pay).isEqualTo(Money.ofMinor(150_000L, IDR)); // 100,000 * 1.5
  }

  @Test
  void ninetyMinutesWeekdayOvertimeProRatesThePartialHourWithinTheSecondTier() {
    // 90 min = 1h @1.5x + 0.5h @2.0x = 150,000 + 100,000*2.0*0.5 = 150,000 + 100,000 = 250,000.
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 90, 0);
    assertThat(pay).isEqualTo(Money.ofMinor(250_000L, IDR));
  }

  // ---------------------------------------------------------------------
  // Overtime — rest-day tiers: 2x hours 1-7, 3x hour 8, 4x hours 9-10
  // ---------------------------------------------------------------------

  @Test
  void tenHoursRestDayOvertimeWalksAllThreeTiers() {
    Money base = Money.ofMinor(17_300_000L, IDR);
    // 600 minutes (10h, the DB max): 7h@2x=1,400,000; 1h@3x=300,000; 2h@4x=800,000. Total
    // 2,500,000.
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 0, 600);
    assertThat(pay).isEqualTo(Money.ofMinor(2_500_000L, IDR));
  }

  @Test
  void fiveHoursRestDayOvertimeStaysEntirelyInTheFirstTier() {
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 0, 300); // 5h
    assertThat(pay).isEqualTo(Money.ofMinor(1_000_000L, IDR)); // 100,000 * 2.0 * 5
  }

  /**
   * The pure {@code overtimeEarning} call independently tier-walks its {@code weekdayMinutes}/
   * {@code restDayMinutes} arguments — a property of ONE call, not a claim about how a real
   * multi-day month should be aggregated (P7 review C1: {@code PayrollRunWriter#appendWorkInputs}
   * NEVER passes a whole month's aggregated minutes in one call; it groups by {@code (work_date,
   * day_kind)} and calls this method ONCE PER DAY, summing the results — see {@code
   * PayrollWorkInputsEndToEndTest} for the multi-day proofs against the real writer path). This
   * test name previously read "in the same month", which could be misread as endorsing monthly
   * aggregation — renamed to avoid that implication.
   */
  @Test
  void weekdayAndRestDayTierTablesAreAppliedIndependentlyWithinOneCall() {
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 60, 300);
    // weekday 1h = 150,000; rest-day 5h = 1,000,000. Total 1,150,000.
    assertThat(pay).isEqualTo(Money.ofMinor(1_150_000L, IDR));
  }

  @Test
  void zeroMinutesBothKindsProducesZeroOvertimePay() {
    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, OVERTIME_PARAMS, 0, 0);
    assertThat(pay).isEqualTo(Money.ofMinor(0L, IDR));
  }

  // ---------------------------------------------------------------------
  // Unpaid leave — PP 36/2021 daily-wage convention (base ÷ work_calendar divisor)
  // ---------------------------------------------------------------------

  @Test
  void unpaidLeaveDeductionIsNegativeBaseTimesDaysOverTheDivisor21() {
    // 5-day-week tenant, divisor 21: 3 unpaid days on a 21,000,000 base = -3,000,000.
    Money base = Money.ofMinor(21_000_000L, IDR);
    Money deduction = calculator.unpaidLeaveEarning(base, 3, 21);
    assertThat(deduction).isEqualTo(Money.ofMinor(-3_000_000L, IDR));
    assertThat(deduction.isNegative()).isTrue();
  }

  @Test
  void unpaidLeaveDeductionUsesTheDivisor25ForASixDayWeekTenant() {
    // 6-day-week tenant, divisor 25: 5 unpaid days on a 25,000,000 base = -5,000,000.
    Money base = Money.ofMinor(25_000_000L, IDR);
    Money deduction = calculator.unpaidLeaveEarning(base, 5, 25);
    assertThat(deduction).isEqualTo(Money.ofMinor(-5_000_000L, IDR));
  }

  @Test
  void unpaidLeaveDeductionRoundsHalfEvenOnceAndStaysNegative() {
    // 10,000,000 / 21 * 1 day = 476,190.47... -> HALF_EVEN rounds to 476,190; then negated.
    Money base = Money.ofMinor(10_000_000L, IDR);
    Money deduction = calculator.unpaidLeaveEarning(base, 1, 21);
    assertThat(deduction).isEqualTo(Money.ofMinor(-476_190L, IDR));
  }

  @Test
  void zeroOrNegativeUnpaidDaysProducesExactlyZero() {
    Money base = Money.ofMinor(10_000_000L, IDR);
    assertThat(calculator.unpaidLeaveEarning(base, 0, 21)).isEqualTo(Money.ofMinor(0L, IDR));
    assertThat(calculator.unpaidLeaveEarning(base, -1, 21)).isEqualTo(Money.ofMinor(0L, IDR));
  }

  // ---------------------------------------------------------------------
  // Unpaid leave — the labor-pay floor (P7 review W1)
  // ---------------------------------------------------------------------

  @Test
  void clampUnpaidDaysCapsAtTheMonthlyDivisor() {
    assertThat(calculator.clampUnpaidDays(31, 21)).isEqualTo(21);
    assertThat(calculator.clampUnpaidDays(21, 21)).isEqualTo(21);
    assertThat(calculator.clampUnpaidDays(20, 21)).isEqualTo(20);
    assertThat(calculator.clampUnpaidDays(25, 25)).isEqualTo(25);
  }

  @Test
  void unpaidLeaveExceedingTheDivisorClampsToExactlyNegativeBasePayNeverMore() {
    // 31 approved unpaid days against a divisor-21 calendar (legal today — a single-month UNPAID
    // request can span up to 31 days): the clamp caps the deduction at -basePay EXACTLY, never
    // driving labor pay negative.
    Money base = Money.ofMinor(21_000_000L, IDR);
    Money deduction = calculator.unpaidLeaveEarning(base, 31, 21);
    assertThat(deduction).isEqualTo(Money.ofMinor(-21_000_000L, IDR));
    assertThat(deduction.negate()).isEqualTo(base); // exactly -basePay, not more
  }

  // ---------------------------------------------------------------------
  // Fidelity against the actual shipped ID-2026.1/ID-2026.2 OVERTIME_HOURLY params (not
  // hand-rolled)
  // ---------------------------------------------------------------------

  @Test
  void theWorkedExampleAlsoHoldsAgainstTheShippedIdDatasetOvertimeHourlyParams() throws Exception {
    var dataset =
        id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.load("ID-2026.2")
            .orElseThrow();
    var rule =
        dataset.rules().stream()
            .filter(r -> "OVERTIME_HOURLY".equals(r.ruleKey()))
            .findFirst()
            .orElseThrow();
    HourlyRateTableParams params = StatutoryParams.hourlyRateTable(rule.paramsJson());
    assertThat(params.monthlyDivisor()).isEqualTo(173);

    Money base = Money.ofMinor(17_300_000L, IDR);
    Money pay = calculator.overtimeEarning(base, params, 180, 0);
    assertThat(pay).isEqualTo(Money.ofMinor(550_000L, IDR));
  }
}
