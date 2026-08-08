package id.co.nativeapp.employee.operator.controller;

import id.co.nativeapp.employee.operator.dto.OperatorRosterEntry;
import id.co.nativeapp.employee.operator.service.OperatorRosterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/operators/roster?businessId=} (ADR 0049 P3b) — the Business-app till's
 * employee-pick PIN picker: who can sign in as an operator at this outlet right now. Rides the SAME
 * gateway route as {@link OperatorSessionController} ({@code /api/v1/operators/**}, POS_ROLES —
 * {@code services/gateway RoutingConfig.operatorsRoute}), so no gateway change is needed; a
 * cashier-role device token can reach it while the owner/manager-only Team list ({@code GET
 * /api/v1/employees}, DASHBOARD_ROLES) stays out of reach.
 *
 * <p><strong>This endpoint deliberately ENUMERATES who can sign in (name only) — that is
 * intentional, unlike {@code POST /api/v1/operators/session}'s uniform-401 non-enumeration
 * hardening.</strong> The roster disclosing "these are the operators at this till" is exactly what
 * a shared kiosk needs to show; it carries no PII and no credential material (rule 6), so a future
 * reviewer should not mistake this for the same leak class {@code /session} guards against.
 */
@Tag(
    name = "Operators",
    description =
        "ADR 0049 P3b: the till's PIN-picker roster — which employees of an outlet can sign in as"
            + " an operator today")
@RestController
@RequestMapping("/api/v1/operators")
public class OperatorRosterController {

  private final OperatorRosterService operatorRosterService;

  public OperatorRosterController(OperatorRosterService operatorRosterService) {
    this.operatorRosterService = operatorRosterService;
  }

  @Operation(
      summary = "List the operators (employeeId + displayName) who can sign in at an outlet today",
      description =
          "Employees of the bound tenant actively assigned to businessId as of today AND carrying"
              + " an operator_pin row (so they can actually sign in). Returns ONLY {employeeId,"
              + " displayName}, sorted by name — no role, status, or PII (rule 6). businessId is"
              + " required (400 when missing/blank/not a UUID).")
  @GetMapping("/roster")
  public List<OperatorRosterEntry> roster(@RequestParam(required = false) String businessId) {
    return operatorRosterService.roster(businessId);
  }
}
