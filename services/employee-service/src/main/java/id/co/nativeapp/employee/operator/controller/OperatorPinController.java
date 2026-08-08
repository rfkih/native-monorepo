package id.co.nativeapp.employee.operator.controller;

import id.co.nativeapp.employee.operator.dto.SetOperatorPinRequest;
import id.co.nativeapp.employee.operator.service.OperatorPinService;
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
 * {@code PUT /api/v1/employees/{employeeId}/operator-pin} (ADR 0049 P1) — owner/manager set or
 * reset an employee's operator PIN. This path falls under the gateway's existing DASHBOARD_ROLES
 * {@code /api/v1/employees/**} route (owner/manager only) — no new gateway route needed for this
 * endpoint (unlike {@code POST /api/v1/operators/session}, see {@code
 * operator.controller.OperatorSessionController}).
 *
 * <p>A thin HTTP adapter: maps the request to a service call and returns {@code 204 No Content} —
 * the PIN is write-only and is never echoed back in any response (rule 6).
 */
@Tag(
    name = "Operators",
    description =
        "ADR 0049 P1: owner/manager set/reset an employee's operator PIN; the till verifies it and"
            + " mints a signed operator session")
@RestController
@RequestMapping("/api/v1/employees")
public class OperatorPinController {

  private final OperatorPinService operatorPinService;

  public OperatorPinController(OperatorPinService operatorPinService) {
    this.operatorPinService = operatorPinService;
  }

  @Operation(
      summary = "Set or reset an employee's operator PIN (owner/manager)",
      description =
          "Owner/manager only. Hashes the PIN (Argon2id) and upserts the employee's operator_pin"
              + " row, resetting any lockout state. The PIN is write-only: never returned, logged,"
              + " or evented.")
  @PutMapping("/{employeeId}/operator-pin")
  public ResponseEntity<Void> setOperatorPin(
      @PathVariable UUID employeeId, @Valid @RequestBody SetOperatorPinRequest request) {
    operatorPinService.setPin(employeeId, request.pin());
    return ResponseEntity.noContent().build();
  }
}
