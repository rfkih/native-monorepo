package id.co.nativeapp.employee.operator.controller;

import id.co.nativeapp.employee.operator.dto.OutletOperatorPolicyResponse;
import id.co.nativeapp.employee.operator.service.OutletOperatorPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/operators/policy?businessId=} (ADR 0049) — whether the till must collect a PIN
 * before minting an operator session at this outlet. Rides the SAME gateway route as {@link
 * OperatorSessionController} / {@link OperatorRosterController} ({@code /api/v1/operators/**},
 * POS_ROLES — {@code services/gateway RoutingConfig.operatorsRoute}), so no gateway change is
 * needed: the till needs this BEFORE it knows whether to show a PIN pad, and it authenticates the
 * same way a cashier device does for every other till surface.
 *
 * <p>An outlet with no policy row on file reads as {@code requirePin: true} (the safe default —
 * today's behavior, unchanged for every outlet until an owner/manager opts it out via {@code PUT
 * /api/v1/employees/outlet-pin-policy/{businessId}}).
 */
@Tag(
    name = "Operators",
    description =
        "ADR 0049: the per-outlet operator-PIN policy — whether the till's sign-in requires a PIN")
@RestController
@RequestMapping("/api/v1/operators")
public class OperatorPolicyController {

  private final OutletOperatorPolicyService outletOperatorPolicyService;

  public OperatorPolicyController(OutletOperatorPolicyService outletOperatorPolicyService) {
    this.outletOperatorPolicyService = outletOperatorPolicyService;
  }

  @Operation(
      summary = "Whether operator sign-in at this outlet currently requires a PIN",
      description =
          "Returns { requirePin: true|false } for the given outlet. No policy row on file reads as"
              + " requirePin=true (the safe default). businessId is required (400 when"
              + " missing/blank/not a UUID).")
  @GetMapping("/policy")
  public OutletOperatorPolicyResponse policy(@RequestParam(required = false) String businessId) {
    return outletOperatorPolicyService.getPolicy(businessId);
  }
}
