package id.co.nativeapp.employee.timeoff.domain;

import java.time.LocalDate;

/**
 * A leave request's {@code startDate}/{@code endDate} fall in different calendar months (Track P
 * Phase P7 review W2 — see {@link LeaveRequest}'s class Javadoc for the full rationale: Phase P7's
 * unpaid-leave payroll proration needs an EXACT per-period day count, and the request's own {@code
 * days} column is one client-supplied total for the WHOLE request, not splittable per month after
 * the fact). Mapped to {@code 422} by {@code EmployeeApiAdvice}. The dates themselves are not PII
 * (rule 6) and are safe to echo back.
 */
public class CrossMonthLeaveRequestException extends RuntimeException {

  public CrossMonthLeaveRequestException(LocalDate startDate, LocalDate endDate) {
    super(
        "Leave request start date "
            + startDate
            + " and end date "
            + endDate
            + " must fall within the SAME calendar month — split a request that spans a month"
            + " boundary into two requests, one per month");
  }
}
