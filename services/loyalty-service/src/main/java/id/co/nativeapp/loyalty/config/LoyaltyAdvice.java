package id.co.nativeapp.loyalty.config;

import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleValidationException;
import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleWriteForbiddenException;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCardNotFoundException;
import id.co.nativeapp.loyalty.member.domain.DuplicateMemberException;
import id.co.nativeapp.loyalty.member.domain.MemberNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The loyalty-service-SPECIFIC RFC 7807 {@link ProblemDetail} advice (ADR 0027) — the fault shapes
 * the shared {@code libs/security ApiExceptionHandler} (validation / {@code IllegalArgumentException}
 * / the non-leaking catch-all) does not own. Mirrors barbershop-service's {@code CatalogAdvice}/
 * {@code PromotionAdvice} style.
 *
 * <ul>
 *   <li>{@link DuplicateMemberException} — enrollment for an already-enrolled phone → {@code 409}.
 *   <li>{@link MemberNotFoundException} — an unknown/cross-tenant member id or phone → {@code 404}.
 *   <li>{@link GiftCardNotFoundException} — an unknown/cross-tenant gift-card code → {@code 404}.
 *   <li>{@link EarnRuleValidationException} — a type/parameter mismatch on earn-rule creation →
 *       {@code 422}.
 *   <li>{@link EarnRuleWriteForbiddenException} — a non-owner/manager earn-rule write → {@code
 *       403}.
 * </ul>
 */
@RestControllerAdvice
public class LoyaltyAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(DuplicateMemberException.class)
  public ProblemDetail handleDuplicateMember(
      DuplicateMemberException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "member-already-enrolled"));
    problem.setTitle("Conflict");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(MemberNotFoundException.class)
  public ProblemDetail handleMemberNotFound(MemberNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "member-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such loyalty member is accessible.");
    return decorate(problem, request);
  }

  @ExceptionHandler(GiftCardNotFoundException.class)
  public ProblemDetail handleGiftCardNotFound(
      GiftCardNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setType(URI.create(TYPE_BASE + "gift-card-not-found"));
    problem.setTitle("Not Found");
    problem.setDetail("No such gift card is accessible.");
    return decorate(problem, request);
  }

  @ExceptionHandler(EarnRuleValidationException.class)
  public ProblemDetail handleEarnRuleInvalid(
      EarnRuleValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "earn-rule-invalid"));
    problem.setTitle("Invalid earn rule request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(EarnRuleWriteForbiddenException.class)
  public ProblemDetail handleEarnRuleForbidden(
      EarnRuleWriteForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "earn-rule-write-forbidden"));
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
