package id.co.nativeapp.employee.config;

import id.co.nativeapp.employee.assignment.ConflictingLegalEmployerException;
import id.co.nativeapp.employee.employee.EmployeeNotFoundException;
import id.co.nativeapp.employee.payroll.IncompletePeriodException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The employee-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice — the fault shapes unique to
 * this service that the SHARED {@code libs/security} {@code ApiExceptionHandler} (which owns the
 * common {@code @Valid}-body validation / illegal-argument / non-leaking catch-all contract) does
 * not handle.
 *
 * <ul>
 *   <li>{@link ConflictingLegalEmployerException} → {@code 409 Conflict}: the same-legal-employer
 *       invariant (ARCHITECTURE.md §2) was violated — the employee already holds a concurrent
 *       assignment under a DIFFERENT legal employer. A {@code 409} (a state conflict), not a {@code
 *       400}, is the right code: the request is well-formed, but it conflicts with the employee's
 *       current assignments.
 *   <li>{@link ConstraintViolationException} → {@code 400}: a bean-validation failure on a request
 *       <em>parameter</em> (e.g. a malformed path/query param under {@code @Validated}), with the
 *       same machine-readable {@code errors} list of {@code {field, message}} the shared
 *       body-validation handler emits.
 *   <li>{@link EmployeeNotFoundException} → {@code 404 Not Found}: the operation references an
 *       employee not visible in the bound tenant (an unknown id, or — invisible under RLS — another
 *       tenant's). A {@code 404} (not a {@code 400}) is the right code: the resource the request
 *       targets does not exist for this caller.
 * </ul>
 *
 * <p>This advice ADDS to the shared one (which is ordered {@code LOWEST_PRECEDENCE}); Spring
 * resolves the most specific {@code @ExceptionHandler} across both, so unknown-argument {@code 400}
 * and the non-leaking catch-all {@code 500} still come from the shared advice. No PII ever reaches
 * a problem detail (rule 6): these handlers carry only structural messages, never an employee's NIK
 * / bank account.
 */
@RestControllerAdvice
public class EmployeeApiAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A concurrent assignment under a different legal employer → 409 Conflict. */
  @ExceptionHandler(ConflictingLegalEmployerException.class)
  public ProblemDetail handleConflictingLegalEmployer(
      ConflictingLegalEmployerException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "conflicting-legal-employer", request);
    problem.setTitle("Conflicting legal employer");
    // The message names only legal-employer ids (not PII): safe to surface as the detail.
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** A payroll run asked to calculate/post an incomplete period → 409 Conflict. */
  @ExceptionHandler(IncompletePeriodException.class)
  public ProblemDetail handleIncompletePeriod(
      IncompletePeriodException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "incomplete-period", request);
    problem.setTitle("Period not complete");
    // The message names only business-unit ids (UUIDs), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** An operation referencing an employee not visible in the bound tenant → 404 Not Found. */
  @ExceptionHandler(EmployeeNotFoundException.class)
  public ProblemDetail handleEmployeeNotFound(
      EmployeeNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "employee-not-found", request);
    problem.setTitle("Employee not found");
    // The message names only the employee id (a UUID), never PII (rule 6).
    problem.setDetail(ex.getMessage());
    return problem;
  }

  /** Bean-validation failures on a request parameter → 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", request);
    problem.setTitle("Validation failed");
    problem.setDetail("One or more parameters are invalid.");
    List<Map<String, String>> errors =
        ex.getConstraintViolations().stream().map(EmployeeApiAdvice::toViolation).toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }

  private static Map<String, String> toViolation(ConstraintViolation<?> violation) {
    String message = violation.getMessage();
    return Map.of(
        "field",
        violation.getPropertyPath().toString(),
        "message",
        message == null ? "invalid" : message);
  }
}
