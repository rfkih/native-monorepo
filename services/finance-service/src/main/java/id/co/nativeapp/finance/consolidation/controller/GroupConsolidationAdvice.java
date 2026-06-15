package id.co.nativeapp.finance.consolidation.controller;

import id.co.nativeapp.finance.consolidation.domain.GroupAccessDeniedException;
import id.co.nativeapp.finance.consolidation.domain.GroupRoleDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The RFC 7807 {@link ProblemDetail} advice for the group-consolidation authz denials (P3d SEAM 4b)
 * — the two fault shapes the SHARED {@code libs/security} {@code ApiExceptionHandler} does not own.
 * It ADDS to the shared advice (which stays the {@link
 * org.springframework.core.Ordered#LOWEST_PRECEDENCE} catch-all): Spring resolves the most specific
 * {@code @ExceptionHandler}, so these denials map correctly while everything else keeps the shared
 * contract.
 *
 * <ul>
 *   <li>{@link GroupRoleDeniedException} → {@code 403} — the requester lacks the group-level role
 *       (a tenant/authZ denial is {@code 403}, ENGINEERING-STANDARDS §1.1).
 *   <li>{@link GroupAccessDeniedException} → {@code 404} — the requester's company does not lead
 *       the group (or the group is unknown). {@code 404}, NOT {@code 403}, so a member probing for
 *       groups it does not lead cannot distinguish "exists but not yours" from "no such group" (no
 *       group-existence disclosure). The {@code detail} is deliberately generic for the same
 *       reason.
 * </ul>
 *
 * <p>Both denials are raised BEFORE the group scope is bound, so the Postgres two-GUC group RLS
 * also fails closed independently — defense in depth.
 */
@RestControllerAdvice
public class GroupConsolidationAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  /** Missing group-level role → 403 (the group scope is never bound). */
  @ExceptionHandler(GroupRoleDeniedException.class)
  public ProblemDetail handleRoleDenied(GroupRoleDeniedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "group-consolidation-forbidden"));
    problem.setTitle("Forbidden");
    problem.setDetail("You do not have the required role for this group operation.");
    return decorate(problem, request);
  }

  /**
   * Not the group's lead (or unknown group) → 404, with a generic detail so neither the path nor
   * the body discloses whether the group exists.
   */
  @ExceptionHandler(GroupAccessDeniedException.class)
  public ProblemDetail handleAccessDenied(
      GroupAccessDeniedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "group-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such group consolidation is accessible.");
    return decorate(problem, request);
  }

  private static ProblemDetail decorate(ProblemDetail problem, HttpServletRequest request) {
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("trace_id");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
