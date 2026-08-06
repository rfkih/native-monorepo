package id.co.nativeapp.org.company.config;

import id.co.nativeapp.org.company.service.InvalidPlanTierException;
import id.co.nativeapp.org.company.service.PlanTierForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC-7807 {@link ProblemDetail} advice for the plan-tier feature (ADR 0044) — the two fault shapes
 * the shared {@code libs/security} {@code ApiExceptionHandler} does not own (mirrors {@code
 * OrgUnitExceptionAdvice} / {@code TenantAccessDeniedAdvice}).
 *
 * <ul>
 *   <li>{@link InvalidPlanTierException} → {@code 422 Unprocessable Entity}, type {@code
 *       invalid-plan-tier}: a well-formed request naming a tier outside the whitelist.
 *   <li>{@link PlanTierForbiddenException} → {@code 403 Forbidden}, type {@code
 *       plan-tier-forbidden}: a non-owner caller.
 * </ul>
 */
@RestControllerAdvice
public class PlanTierAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** A tier value outside {@code Company.PLAN_TIERS} → 422. */
  @ExceptionHandler(InvalidPlanTierException.class)
  public ProblemDetail handleInvalidPlanTier(
      InvalidPlanTierException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "invalid-plan-tier"));
    problem.setTitle("Unprocessable Entity");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  /** A non-owner caller attempting the plan-tier setter → 403. */
  @ExceptionHandler(PlanTierForbiddenException.class)
  public ProblemDetail handlePlanTierForbidden(
      PlanTierForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "plan-tier-forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  private static ProblemDetail decorate(ProblemDetail problem, HttpServletRequest request) {
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
