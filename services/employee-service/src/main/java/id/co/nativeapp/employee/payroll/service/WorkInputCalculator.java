package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams.HourlyTier;
import id.co.nativeapp.money.Money;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The pure, config-driven work-input engine (Track P phase P7) — mirrors {@link
 * GrossToNetCalculator}/{@link LaborCostAllocator}: ALGORITHMS only, zero statutory figures (HR-9),
 * no repository, no {@code @Transactional}, a deterministic function of its inputs so {@code
 * PayrollRunWriter} can freeze exactly what it consumed and a re-run is byte-identical (HR-7).
 *
 * <p><strong>Unpaid-leave day counting convention (P7 review W2).</strong> A {@code leave_request}
 * is now constrained to a SINGLE calendar month at creation ({@code LeaveRequest}'s constructor —
 * {@code CrossMonthLeaveRequestException}), so a request's own {@code days} column is always wholly
 * attributable to ONE payroll period; {@code PayrollRunWriter} sums that column directly across
 * every APPROVED {@code UNPAID} request whose month matches the run's — it does NOT re-derive a
 * workday count from the date range (a second, potentially-divergent definition of "how many days"
 * would only invite drift; the single-month invariant makes it unnecessary). {@link
 * #unpaidLeaveEarning} therefore takes the ALREADY-SUMMED day count as a plain {@code int}.
 *
 * <p><strong>Overtime minute pro-ration convention (statutory spec / this phase).</strong> PP
 * 35/2021 defines multiplier TIERS in whole hours (weekday: 1.5x hour 1, 2x thereafter; rest-day:
 * 2x hours 1-7, 3x hour 8, 4x hours 9-10). An approved {@code overtime_entry} carries MINUTES,
 * which may not land on an exact hour boundary. This engine PRO-RATES a partial hour WITHIN its
 * tier by the fraction of the hour actually worked (minutes/60) rather than rounding up/down to the
 * nearest whole hour — e.g. 90 minutes weekday overtime is exactly 1.0 hour at 1.5x + 0.5 hour at
 * 2.0x, not 2 hours rounded at some single rate. This is the documented, chosen convention (a
 * regulation-cited worked example in the test suite proves the arithmetic); rounding UP every
 * partial hour to the next whole tier is a plausible alternative some employers use but is NOT what
 * this engine does.
 */
@Component
public class WorkInputCalculator {

  private static final int MINUTES_PER_HOUR = 60;

  /**
   * The unpaid-leave EARNING amount (Track P phase P7): {@code -(basePay × unpaidDays /
   * monthlyDivisor)} — a SIGNED-NEGATIVE Money (PP 36/2021 daily-wage convention, the tenant's
   * {@code work_calendar.monthly_divisor}, 21 or 25). Rounds once via {@link Money#mulDiv}
   * (HALF_EVEN) then negates; never rounds a second time. Returns zero (never a negative-zero
   * surprise) when {@code unpaidDays <= 0}.
   */
  public Money unpaidLeaveEarning(Money basePay, int unpaidDays, int monthlyDivisor) {
    if (unpaidDays <= 0) {
      return Money.ofMinor(0L, basePay.currency());
    }
    return basePay.mulDiv(unpaidDays, monthlyDivisor).negate();
  }

  /**
   * The overtime EARNING amount (PP 35/2021, Track P phase P7): the tenant's OFFICIAL {@code
   * OVERTIME_HOURLY} rule params applied to the employee's APPROVED overtime minutes this month,
   * split by {@code weekdayMinutes}/{@code restDayMinutes} (the two {@code day_kind}s an {@code
   * overtime_entry} may carry). {@code hourlyRate = basePay × 1/monthlyDivisor} (the params' OWN
   * divisor, e.g. 173 — NOT the {@code work_calendar} divisor); each tier contributes {@code
   * hourlyRate × tierMultiplier × (tierMinutes / 60)}, minute-pro-rated within the tier (see the
   * class javadoc). Always non-negative.
   */
  public Money overtimeEarning(
      Money basePay,
      StatutoryParams.HourlyRateTableParams params,
      int weekdayMinutes,
      int restDayMinutes) {
    Money hourlyRate = basePay.mulDiv(1L, params.monthlyDivisor());
    Money total = Money.ofMinor(0L, basePay.currency());
    total = total.plus(tierPay(hourlyRate, params.tiers().get("weekday"), weekdayMinutes));
    total = total.plus(tierPay(hourlyRate, params.tiers().get("rest_day"), restDayMinutes));
    return total;
  }

  private Money tierPay(Money hourlyRate, List<HourlyTier> tiers, int totalMinutes) {
    Money zero = Money.ofMinor(0L, hourlyRate.currency());
    if (tiers == null || totalMinutes <= 0) {
      return zero;
    }
    Money total = zero;
    int remainingMinutes = totalMinutes;
    for (HourlyTier tier : tiers) {
      if (remainingMinutes <= 0) {
        break;
      }
      int tierCapMinutes =
          tier.hours() == null ? remainingMinutes : tier.hours() * MINUTES_PER_HOUR;
      int minutesInTier = Math.min(remainingMinutes, tierCapMinutes);
      Money tierPay =
          hourlyRate.applyBasisPoints(tier.multiplierBp()).mulDiv(minutesInTier, MINUTES_PER_HOUR);
      total = total.plus(tierPay);
      remainingMinutes -= minutesInTier;
    }
    return total;
  }
}
