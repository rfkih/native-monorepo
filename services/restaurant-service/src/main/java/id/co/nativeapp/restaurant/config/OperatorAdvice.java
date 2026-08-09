package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.sale.domain.OperatorMismatchException;
import id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException;
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
 * ADR 0049 P2/P4 RFC-7807 advice — narrow, feature-scoped, ordered ahead of the shared {@code
 * libs/security ApiExceptionHandler} catch-all (mirrors {@code OrderAdvice}/{@code
 * StockExceptionHandler}'s style).
 *
 * <ul>
 *   <li>{@link OperatorMismatchException} — a verified operator-session token names the wrong
 *       tenant/outlet for this sale → {@code 409 Conflict} ({@code operator-mismatch}). Detail does
 *       NOT echo the token's claims (avoids confirming which company/outlet ids exist).
 *   <li>{@link OperatorRequiredException} — a device (outlet-terminal) sale attempted with no
 *       verified operator session → {@code 409 Conflict} ({@code operator-required}, ADR 0049 P4).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OperatorAdvice {

  @ExceptionHandler(OperatorMismatchException.class)
  public ProblemDetail handleOperatorMismatch(
      OperatorMismatchException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(OperatorMismatchException.TYPE));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setTitle("Operator session mismatch");
    problem.setDetail(
        "The operator session does not match this sale's tenant/outlet; re-select the operator and"
            + " retry.");
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }

  @ExceptionHandler(OperatorRequiredException.class)
  public ProblemDetail handleOperatorRequired(
      OperatorRequiredException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(OperatorRequiredException.TYPE));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setTitle("Operator session required");
    problem.setDetail(
        "This outlet terminal requires a verified operator (cashier PIN) session to ring a sale.");
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
