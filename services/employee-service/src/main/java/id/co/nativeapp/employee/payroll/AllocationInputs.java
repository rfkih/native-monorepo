package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * Inputs to the pure {@link LaborCostAllocator}. The {@link PayrollRunWriter} resolves a person's
 * concurrent assignments (each to an outlet org_unit + its legal employer) and the per-outlet
 * attributable earnings; the allocator distributes the person's TOTAL employer-borne labor cost
 * across the outlets with an exact-sum largest-remainder rule (design §4).
 */
public final class AllocationInputs {

  private AllocationInputs() {
    // value-type holder
  }

  /**
   * One outlet the person is assigned to, with its legal employer and the earnings attributable to
   * it (base prorated across concurrent assignments + directly-attributable per-metric earnings).
   *
   * @param outletOrgUnitId the outlet org unit
   * @param legalEmployerId the outlet's legal employer (from the org read model)
   * @param glAccount the GL account the labor cost posts to (the labor-cost component's GL)
   * @param attributableEarnings earnings attributable to this outlet (drives the share)
   */
  public record OutletShare(
      UUID outletOrgUnitId, UUID legalEmployerId, String glAccount, Money attributableEarnings) {}

  /**
   * The allocation request for one person.
   *
   * @param employeeId the person
   * @param totalLaborCost the TOTAL employer-borne labor cost to distribute (gross + employer legs)
   * @param totalEarnings the person's total attributable earnings (the share denominator)
   * @param outlets the outlets to allocate across (at least one)
   */
  public record PersonAllocation(
      UUID employeeId, Money totalLaborCost, Money totalEarnings, List<OutletShare> outlets) {}
}
