package id.co.nativeapp.employee.operator.controller;

import id.co.nativeapp.employee.operator.dto.OperatorSessionRequest;
import id.co.nativeapp.employee.operator.dto.OperatorSessionResponse;
import id.co.nativeapp.employee.operator.service.OperatorSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/operators/session} (ADR 0049) — the till's employee-pick (+ PIN, when the
 * outlet's policy requires one) sign-in. This path rides a gateway route ({@code
 * /api/v1/operators/**}, POS_ROLES — {@code services/gateway RoutingConfig.operatorsRoute}),
 * distinct from the owner/manager-only {@code /api/v1/employees/**} route the PIN set/reset and
 * outlet-policy endpoints use.
 *
 * <p>No vertical consumes the minted token yet (P2) — this endpoint exists so the till can obtain
 * one, nothing more.
 */
@Tag(
    name = "Operators",
    description =
        "ADR 0049: owner/manager set/reset an employee's operator PIN and the per-outlet PIN"
            + " policy; the till verifies (or, at a no-PIN outlet, simply identifies) the operator"
            + " and mints a signed operator session")
@RestController
@RequestMapping("/api/v1/operators")
public class OperatorSessionController {

  private final OperatorSessionService operatorSessionService;

  public OperatorSessionController(OperatorSessionService operatorSessionService) {
    this.operatorSessionService = operatorSessionService;
  }

  @Operation(
      summary = "Verify an operator PIN (if required) and mint a signed operator session",
      description =
          "Verifies (employeeId, pin) for the given outlet WHEN the outlet's policy requires a"
              + " PIN (GET /api/v1/operators/policy): rejects a locked PIN (423), an employee not"
              + " actively assigned to the outlet (403), or a wrong/unset PIN (401, uniform"
              + " message). At a no-PIN outlet the pin is ignored entirely. On success mints a"
              + " short-lived HMAC-signed operator token carrying the operator's own linked login"
              + " id, display name, and role. No vertical consumes this token yet.")
  @PostMapping("/session")
  public OperatorSessionResponse createSession(@Valid @RequestBody OperatorSessionRequest request) {
    return operatorSessionService.createSession(
        request.businessId(), request.employeeId(), request.pin());
  }
}
