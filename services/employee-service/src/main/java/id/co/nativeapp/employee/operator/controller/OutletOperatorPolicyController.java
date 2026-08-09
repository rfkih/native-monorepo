package id.co.nativeapp.employee.operator.controller;

import id.co.nativeapp.employee.operator.dto.SetOutletOperatorPolicyRequest;
import id.co.nativeapp.employee.operator.service.OutletOperatorPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/v1/employees/outlet-pin-policy/{businessId}} (ADR 0049) — owner/manager set the
 * per-outlet operator-PIN policy. This path falls under the gateway's existing DASHBOARD_ROLES
 * {@code /api/v1/employees/**} route (owner/manager only) — no new gateway route needed, mirroring
 * {@link id.co.nativeapp.employee.operator.controller.OperatorPinController}'s shape.
 *
 * <p>A thin HTTP adapter: maps the request to a service call and returns {@code 204 No Content}.
 */
@Tag(
    name = "Operators",
    description =
        "ADR 0049: the per-outlet operator-PIN policy — whether the till's sign-in requires a PIN")
@RestController
@RequestMapping("/api/v1/employees")
public class OutletOperatorPolicyController {

  private final OutletOperatorPolicyService outletOperatorPolicyService;

  public OutletOperatorPolicyController(OutletOperatorPolicyService outletOperatorPolicyService) {
    this.outletOperatorPolicyService = outletOperatorPolicyService;
  }

  @Operation(
      summary = "Set the per-outlet operator-PIN policy (owner/manager)",
      description =
          "Owner/manager only. Upserts the outlet's outlet_operator_policy row. requirePin=false"
              + " means the till's employee-pick sign-in trusts the pick alone, with no PIN check.")
  @PutMapping("/outlet-pin-policy/{businessId}")
  public ResponseEntity<Void> setPolicy(
      @PathVariable UUID businessId, @Valid @RequestBody SetOutletOperatorPolicyRequest request) {
    outletOperatorPolicyService.setPolicy(businessId, request.requirePin());
    return ResponseEntity.noContent().build();
  }
}
