package id.co.nativeapp.employee.operator.domain;

/**
 * A failed operator-session PIN verification — mapped to {@code 401 Unauthorized} by the
 * employee-service advice with a UNIFORM message (no enumeration, ADR 0049 P1): this exception is
 * thrown identically whether the {@code employeeId} has no {@code operator_pin} row at all, or has
 * one and the supplied PIN simply did not match. Neither the message nor the HTTP response ever
 * distinguishes the two cases, so a caller cannot use this endpoint to discover which employees
 * have a PIN configured. Deliberately carries NO arguments (unlike the fleet's usual id-in-message
 * convention) so nothing case-specific can leak.
 */
public class InvalidOperatorPinException extends RuntimeException {

  public InvalidOperatorPinException() {
    super("Invalid operator PIN");
  }
}
