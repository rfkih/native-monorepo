package id.co.nativeapp.restaurant.bill.controller;

import id.co.nativeapp.restaurant.bill.domain.BillHasPaidLinesException;
import id.co.nativeapp.restaurant.bill.domain.BillLinePaidException;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservedException;
import id.co.nativeapp.restaurant.bill.domain.BillMutationForbiddenException;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.domain.BillNotOpenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps bill-domain exceptions to RFC-7807 {@link ProblemDetail} responses.
 *
 * <p>Ordered before the shared {@link id.co.nativeapp.security.ApiExceptionHandler} (which owns the
 * catch-all {@link Exception} → 500 mapping) so these narrower handlers win.
 *
 * <ul>
 *   <li>{@link BillNotFoundException} → {@code 404 Not Found}
 *   <li>{@link BillNotOpenException} → {@code 409 Conflict}
 *   <li>{@link BillLineReservationConflictException} → {@code 409 Conflict}
 *   <li>{@link BillLineReservedException} → {@code 409 Conflict}
 *   <li>{@link BillMutationForbiddenException} → {@code 403 Forbidden} (open-bill lockdown)
 *   <li>{@link BillHasPaidLinesException} → {@code 409 Conflict}
 *   <li>{@link BillLinePaidException} → {@code 409 Conflict}
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillExceptionHandler {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(BillNotFoundException.class)
  public ProblemDetail handleBillNotFound(BillNotFoundException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "bill-not-found", req);
    problem.setTitle("Bill not found");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    return problem;
  }

  @ExceptionHandler(BillNotOpenException.class)
  public ProblemDetail handleBillNotOpen(BillNotOpenException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "bill-not-open", req);
    problem.setTitle("Bill is not open");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("currentStatus", ex.getCurrentStatus());
    return problem;
  }

  @ExceptionHandler(BillLineReservationConflictException.class)
  public ProblemDetail handleBillLineReservationConflict(
      BillLineReservationConflictException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "bill-line-reservation-conflict", req);
    problem.setTitle("Bill lines are already claimed by a concurrent payment");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("expectedUnpaidLines", ex.getExpectedUnpaidLines());
    problem.setProperty("reservedLines", ex.getReservedLines());
    return problem;
  }

  @ExceptionHandler(BillLineReservedException.class)
  public ProblemDetail handleBillLineReserved(
      BillLineReservedException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "bill-line-reserved", req);
    problem.setTitle("Bill line is reserved by an in-flight payment");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("lineId", ex.getLineId().toString());
    problem.setProperty("pendingPaymentId", ex.getPendingPaymentId().toString());
    return problem;
  }

  @ExceptionHandler(BillMutationForbiddenException.class)
  public ProblemDetail handleBillMutationForbidden(
      BillMutationForbiddenException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "bill-mutation-forbidden", req);
    problem.setTitle("Owner or manager role required");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("action", ex.getAction());
    return problem;
  }

  @ExceptionHandler(BillHasPaidLinesException.class)
  public ProblemDetail handleBillHasPaidLines(
      BillHasPaidLinesException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "bill-has-paid-lines", req);
    problem.setTitle("Bill has paid lines");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("paidLineCount", ex.getPaidLineCount());
    return problem;
  }

  @ExceptionHandler(BillLinePaidException.class)
  public ProblemDetail handleBillLinePaid(BillLinePaidException ex, HttpServletRequest req) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "bill-line-paid", req);
    problem.setTitle("Bill line is already paid");
    problem.setDetail(ex.getMessage());
    problem.setProperty("billId", ex.getBillId().toString());
    problem.setProperty("lineId", ex.getLineId().toString());
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(TYPE_BASE + slug));
    pd.setInstance(URI.create(req.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      pd.setProperty("traceId", traceId);
    }
    return pd;
  }
}
