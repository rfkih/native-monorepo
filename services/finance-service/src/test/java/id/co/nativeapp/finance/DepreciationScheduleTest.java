package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.assets.service.DepreciationSchedule;
import id.co.nativeapp.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the straight-line schedule math (Phase 6) — the cumulative-rounding exact-sum
 * guarantee for awkward bases, and the month-index edges. Pure math, no Spring/DB.
 */
class DepreciationScheduleTest {

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  /** Σ amount(k) for k=1..n must equal the base EXACTLY — the telescoping guarantee. */
  private static void assertExactSum(long baseMinor, int n) {
    Money base = idr(baseMinor);
    long sum = 0L;
    for (int k = 1; k <= n; k++) {
      long amount = DepreciationSchedule.amountForMonth(base, k, n).amountMinor();
      assertThat(amount)
          .as("month %d of %d for base %d", k, n, baseMinor)
          .isGreaterThanOrEqualTo(0L);
      sum = Math.addExact(sum, amount);
    }
    assertThat(sum).as("Σ schedule for base %d over %d", baseMinor, n).isEqualTo(baseMinor);
  }

  @Test
  void evenBaseSplitsEvenly() {
    Money base = idr(12_000_000L);
    for (int k = 1; k <= 12; k++) {
      assertThat(DepreciationSchedule.amountForMonth(base, k, 12).amountMinor())
          .isEqualTo(1_000_000L);
    }
    assertExactSum(12_000_000L, 12);
  }

  @Test
  void awkwardBasesSumExactly() {
    assertExactSum(10_000_001L, 12); // one leftover minor unit spread, not lumped
    assertExactSum(100L, 7);
    assertExactSum(1L, 12); // base smaller than the schedule — mostly zero months
    assertExactSum(11L, 12);
    assertExactSum(999_999_999_999L, 37);
    assertExactSum(5L, 60);
  }

  @Test
  void tinyBaseYieldsZeroMonthsButStillSumsExactly() {
    Money base = idr(1L);
    long positiveMonths = 0;
    for (int k = 1; k <= 12; k++) {
      if (DepreciationSchedule.amountForMonth(base, k, 12).isPositive()) {
        positiveMonths++;
      }
    }
    assertThat(positiveMonths).isEqualTo(1); // exactly one month carries the single minor unit
  }

  @Test
  void monthIndexIsOneBasedFromTheStartPeriod() {
    assertThat(DepreciationSchedule.monthIndex("2026-08", "2026-08")).isEqualTo(1);
    assertThat(DepreciationSchedule.monthIndex("2026-08", "2026-09")).isEqualTo(2);
    assertThat(DepreciationSchedule.monthIndex("2026-08", "2027-07")).isEqualTo(12);
    assertThat(DepreciationSchedule.monthIndex("2026-08", "2026-07")).isEqualTo(0); // before start
    assertThat(DepreciationSchedule.monthIndex("2026-08", "2025-08")).isEqualTo(-11);
  }

  @Test
  void isDueCoversExactlyTheScheduleWindow() {
    assertThat(DepreciationSchedule.isDue("2026-08", "2026-07", 12)).isFalse(); // before start
    assertThat(DepreciationSchedule.isDue("2026-08", "2026-08", 12)).isTrue(); // month 1
    assertThat(DepreciationSchedule.isDue("2026-08", "2027-07", 12)).isTrue(); // month 12 (last)
    assertThat(DepreciationSchedule.isDue("2026-08", "2027-08", 12)).isFalse(); // after end
  }

  @Test
  void outOfRangeMonthIndexIsRejected() {
    Money base = idr(1_000L);
    assertThatThrownBy(() -> DepreciationSchedule.amountForMonth(base, 0, 12))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DepreciationSchedule.amountForMonth(base, 13, 12))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DepreciationSchedule.amountForMonth(base, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
