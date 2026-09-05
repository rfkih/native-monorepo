package id.co.nativeapp.restaurant.integrity.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which operators stand out from their colleagues — the arithmetic behind every signal in
 * the leak report that names a person.
 *
 * <p>Pure and static, and pinned by unit tests, because this is where the feature can do real harm.
 * A rate comparison that is subtly wrong does not crash; it puts a name in front of an owner.
 *
 * <p><strong>Compared against the REST of the outlet, never the outlet as a whole.</strong> Include
 * an actor in their own baseline and a genuine outlier drags the baseline toward themselves — at
 * three cashiers, the one voiding half their sales lifts the outlet average enough to look
 * ordinary. Excluding them makes the comparison say what it is meant to say: "unlike everyone else
 * here".
 *
 * <p><strong>Integer throughout (rule 8-adjacent).</strong> Rates are never materialised as
 * fractions. {@code a/b >= factor * c/d} is evaluated as {@code a * d >= factor * c * b}, so the
 * verdict is exact and cannot turn on a floating-point rounding step.
 */
public final class OperatorOutliers {

  private OperatorOutliers() {
    // pure functions
  }

  /**
   * One operator's share of something, kept as a numerator and denominator rather than a rate.
   *
   * @param actor the operator's identifier
   * @param numerator what they did (voids, refunds, cash sales) or the discount they gave
   * @param denominator the opportunity they had (payments taken, gross rung)
   * @param valueMinor the money behind the numerator, carried through for display only
   */
  public record OperatorRate(String actor, long numerator, long denominator, long valueMinor) {}

  /**
   * The operators whose rate is at least {@code factor} times the rate of everyone else at the
   * outlet, ordered by the money involved (largest first).
   *
   * <p>Three guards keep this from naming people it should not:
   *
   * <ul>
   *   <li><strong>A minimum count.</strong> Two voids out of three sales is a 67% rate and means
   *       nothing. Without a floor, the quietest operator tops every list by arithmetic alone.
   *   <li><strong>Somebody to compare against.</strong> With one operator there is no
   *       rest-of-outlet, and a lone cashier is not an outlier — they are the entire baseline.
   *       Returns nothing.
   *   <li><strong>A real denominator.</strong> An operator with no opportunity has no rate.
   * </ul>
   *
   * @param rates every operator's figures for one measure
   * @param factor how many times the others' rate counts as standing out
   * @param minCount the fewest events an operator must have before their rate means anything
   */
  public static List<OperatorRate> outliers(List<OperatorRate> rates, long factor, long minCount) {
    long totalNumerator = 0L;
    long totalDenominator = 0L;
    for (OperatorRate rate : rates) {
      totalNumerator = Math.addExact(totalNumerator, rate.numerator());
      totalDenominator = Math.addExact(totalDenominator, rate.denominator());
    }

    List<OperatorRate> flagged = new ArrayList<>();
    for (OperatorRate rate : rates) {
      if (rate.numerator() < minCount || rate.denominator() <= 0) {
        continue;
      }
      long othersNumerator = totalNumerator - rate.numerator();
      long othersDenominator = totalDenominator - rate.denominator();
      if (othersDenominator <= 0) {
        // Nobody else worked this window. There is no "unlike everyone else" to be had.
        continue;
      }
      // rate.num / rate.den >= factor * othersNum / othersDen, cross-multiplied so no division
      // (and therefore no rounding) is involved in the verdict.
      long mine = Math.multiplyExact(rate.numerator(), othersDenominator);
      long theirs =
          Math.multiplyExact(factor, Math.multiplyExact(othersNumerator, rate.denominator()));
      if (mine >= theirs) {
        flagged.add(rate);
      }
    }
    flagged.sort(
        Comparator.comparingLong(OperatorRate::valueMinor)
            .reversed()
            .thenComparing(OperatorRate::actor));
    return flagged;
  }

  /** Σ of the money behind a set of flagged operators. */
  public static long totalValue(List<OperatorRate> rates) {
    long total = 0L;
    for (OperatorRate rate : rates) {
      total = Math.addExact(total, rate.valueMinor());
    }
    return total;
  }
}
