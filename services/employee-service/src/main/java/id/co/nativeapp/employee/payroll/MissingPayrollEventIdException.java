package id.co.nativeapp.employee.payroll;

/**
 * A consumed payroll-input event arrived WITHOUT a valid {@code id} header (the durable event
 * UUID). That is a producer-side contract violation, so the consumer FAILS CLOSED: this is
 * classified non-retryable and routed straight to {@code <topic>.DLT} rather than processed without
 * an idempotency key (exactly {@code OrgUnitEventListener}'s pattern).
 */
public class MissingPayrollEventIdException extends RuntimeException {

  public MissingPayrollEventIdException(String message) {
    super(message);
  }

  public MissingPayrollEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
