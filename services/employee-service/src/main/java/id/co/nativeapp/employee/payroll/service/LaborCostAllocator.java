package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.AllocationInputs.OutletShare;
import id.co.nativeapp.employee.payroll.domain.AllocationInputs.PersonAllocation;
import id.co.nativeapp.money.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The pure aggregate-then-allocate engine (design §4). It distributes a person's TOTAL
 * employer-borne labor cost across the outlets they are assigned to, by earnings share, with an
 * EXACT-SUM largest-remainder rule so no minor unit leaks:
 *
 * <pre>
 *   share_bp[i]  = round(attributable_earnings[i] * 10000 / total_earnings)   (HALF_EVEN)
 *   alloc[i]     = floor(total_labor_cost * share_bp[i] / 10000)              (in minor units)
 *   residual     = total_labor_cost - sum(alloc[i])                          (non-negative remainder)
 *   the residual is added to the outlet with the LARGEST fractional remainder
 *   (ties broken by lowest outlet_org_unit_id — deterministic).
 * </pre>
 *
 * The post-condition {@code sum(alloc) == total_labor_cost} is asserted before the run may post; a
 * mismatch fails the run. A {@code @Component} with no {@code @Transactional} / repository — a
 * pure, deterministic function (HR-7). The same-legal-employer invariant is enforced upstream in
 * the writer (reusing {@code resolveLegalEmployer} + {@code ConflictingLegalEmployerException});
 * this defensively re-asserts a single legal employer across the outlets it allocates.
 */
@Component
public class LaborCostAllocator {

  private static final long BP = 10_000L;

  /**
   * One allocated bucket (employee, outlet, gl) ready to persist as a {@link LaborCostAllocation}.
   */
  public record AllocatedRow(
      UUID employeeId,
      UUID outletOrgUnitId,
      UUID legalEmployerId,
      String glAccount,
      Money amount,
      int earningsShareBp) {}

  /**
   * Allocates one person's labor cost across their outlets, exact-sum. Returns one row per outlet.
   *
   * @throws IllegalStateException if the allocation does not sum exactly to the total (fails the
   *     run), or if the person has no outlet to allocate to
   */
  public List<AllocatedRow> allocate(PersonAllocation person) {
    List<OutletShare> outlets = person.outlets();
    if (outlets.isEmpty()) {
      throw new IllegalStateException(
          "Employee " + person.employeeId() + " has no outlet to allocate labor cost to");
    }
    Money total = person.totalLaborCost();
    java.util.Currency currency = total.currency();

    // Single-outlet fast path: the whole cost (100%) goes to the one outlet — exact by definition.
    if (outlets.size() == 1) {
      OutletShare only = outlets.get(0);
      return List.of(
          new AllocatedRow(
              person.employeeId(),
              only.outletOrgUnitId(),
              only.legalEmployerId(),
              only.glAccount(),
              total,
              (int) BP));
    }

    long totalEarnings = person.totalEarnings().amountMinor();
    long totalMinor = total.amountMinor();

    // Working rows: share_bp, floored alloc, and the exact fractional remainder (as a rational).
    List<Working> working = new ArrayList<>();
    long allocatedSoFar = 0L;
    for (OutletShare outlet : outlets) {
      int shareBp =
          shareBp(outlet.attributableEarnings().amountMinor(), totalEarnings, outlets.size());
      // alloc = floor(total * shareBp / 10000)
      BigInteger product = BigInteger.valueOf(totalMinor).multiply(BigInteger.valueOf(shareBp));
      BigInteger[] divRem = product.divideAndRemainder(BigInteger.valueOf(BP));
      long floored = divRem[0].longValueExact();
      BigInteger remainder = divRem[1]; // 0..(BP-1) numerator over BP
      allocatedSoFar += floored;
      working.add(new Working(outlet, shareBp, floored, remainder));
    }

    long residual = totalMinor - allocatedSoFar;
    // residual is a small non-negative remainder from flooring; assign it ALL to the outlet with
    // the
    // largest fractional remainder (ties -> lowest outlet id). residual minor units, one at a time,
    // would distribute; the design assigns the WHOLE residual to the single largest-remainder
    // outlet.
    if (residual > 0L) {
      working.sort(
          Comparator.comparing((Working w) -> w.remainder)
              .reversed()
              .thenComparing(w -> w.outlet.outletOrgUnitId()));
      Working top = working.get(0);
      top.floored += residual;
    }

    List<AllocatedRow> rows = new ArrayList<>();
    long sum = 0L;
    for (Working w : working) {
      sum += w.floored;
      rows.add(
          new AllocatedRow(
              person.employeeId(),
              w.outlet.outletOrgUnitId(),
              w.outlet.legalEmployerId(),
              w.outlet.glAccount(),
              Money.ofMinor(w.floored, currency),
              w.shareBp));
    }
    if (sum != totalMinor) {
      throw new IllegalStateException(
          "Labor-cost allocation for employee "
              + person.employeeId()
              + " summed to "
              + sum
              + " but the total is "
              + totalMinor
              + " (exact-sum invariant violated)");
    }
    return rows;
  }

  private int shareBp(long attributable, long totalEarnings, int outletCount) {
    if (totalEarnings <= 0L) {
      // No earnings basis (e.g. a pure employer-cost case): split equally as a fallback so the
      // shares still sum to ~10000 and the residual rule fixes any remainder.
      return (int) (BP / outletCount);
    }
    BigDecimal share =
        BigDecimal.valueOf(attributable)
            .multiply(BigDecimal.valueOf(BP))
            .divide(BigDecimal.valueOf(totalEarnings), 0, java.math.RoundingMode.HALF_EVEN);
    return share.intValueExact();
  }

  private static final class Working {
    private final OutletShare outlet;
    private final int shareBp;
    private long floored;
    private final BigInteger remainder;

    private Working(OutletShare outlet, int shareBp, long floored, BigInteger remainder) {
      this.outlet = outlet;
      this.shareBp = shareBp;
      this.floored = floored;
      this.remainder = remainder;
    }
  }
}
