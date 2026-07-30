package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.money.Money;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The straight-line schedule math (Phase 6, ADR 0020) — pure static functions, no Spring/DB.
 *
 * <p><strong>Exact-sum by cumulative rounding.</strong> Naively posting {@code base / N} each month
 * drifts by rounding; lumping the remainder into the last month distorts it. Instead each month's
 * amount is the DIFFERENCE OF CUMULATIVE TARGETS:
 *
 * <pre>amount(k) = round(B·k/N) − round(B·(k−1)/N)</pre>
 *
 * with a single HALF_EVEN rounding per cumulative target ({@link Money#mulDiv}). By telescoping,
 * {@code Σ amount(k) for k=1..N = round(B·N/N) − round(0) = B} <em>exactly</em> — the remainder
 * minor units spread evenly across the schedule instead of piling up anywhere. An individual month
 * can round to zero when {@code B < N}; the run records the step but posts no entry.
 *
 * <p><strong>Month index.</strong> {@code k = monthsBetween(startPeriod, period) + 1} (1-based); an
 * item is due in a period iff {@code 1 ≤ k ≤ N}. Periods are independent (the cumulative formula
 * needs only k), so a missed month is caught up by simply running that period — no ordering
 * constraint between runs.
 */
public final class DepreciationSchedule {

  private DepreciationSchedule() {
    // static only
  }

  /**
   * The schedule amount for 1-based month {@code k} of {@code n}, of base {@code b} — the
   * difference of cumulative HALF_EVEN targets (see class doc). Exact-sum: Σ over k = b.
   *
   * @throws IllegalArgumentException if {@code k} is outside {@code [1, n]} or {@code n <= 0}
   */
  public static Money amountForMonth(Money base, int k, int n) {
    Objects.requireNonNull(base, "base");
    if (n <= 0) {
      throw new IllegalArgumentException("schedule length must be strictly positive: " + n);
    }
    if (k < 1 || k > n) {
      throw new IllegalArgumentException("month index " + k + " outside schedule [1, " + n + "]");
    }
    Money cumulativeK = base.mulDiv(k, n);
    Money cumulativePrev = base.mulDiv(k - 1L, n);
    return cumulativeK.minus(cumulativePrev);
  }

  /**
   * The 1-based month index of {@code period} on a schedule starting at {@code startPeriod}
   * (inclusive): {@code monthsBetween + 1}. May be {@code <= 0} (before the start) or {@code > n}
   * (after the end) — the caller checks {@link #isDue}.
   */
  public static int monthIndex(String startPeriod, String period) {
    YearMonth start = YearMonth.parse(startPeriod);
    YearMonth target = YearMonth.parse(period);
    long months = ChronoUnit.MONTHS.between(start, target);
    return Math.toIntExact(Math.addExact(months, 1L));
  }

  /**
   * Whether a schedule of {@code n} months starting at {@code startPeriod} is due in {@code
   * period}.
   */
  public static boolean isDue(String startPeriod, String period, int n) {
    int k = monthIndex(startPeriod, period);
    return k >= 1 && k <= n;
  }
}
