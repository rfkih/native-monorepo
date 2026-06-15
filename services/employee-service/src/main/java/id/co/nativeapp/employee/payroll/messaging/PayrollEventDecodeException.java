package id.co.nativeapp.employee.payroll.messaging;

/**
 * A non-retryable failure decoding a consumed payroll-input event ({@code PeriodSealed} / {@code
 * MetricPublished}). Classified non-retryable by the Kafka error handler so a poison payload lands
 * deterministically on {@code <topic>.DLT} rather than looping in place and stalling the gate.
 */
public class PayrollEventDecodeException extends RuntimeException {

  public PayrollEventDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
