package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.barbershop.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.barbershop.promotion.domain.CouponInvalidException;
import id.co.nativeapp.barbershop.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.barbershop.promotion.domain.PromoRuleValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The promotion-feature-specific RFC 7807 {@link ProblemDetail} advice (ADR 0026) — the fault
 * shapes the shared {@code libs/security ApiExceptionHandler} does not own, mirroring {@link
 * CatalogAdvice} / {@link TicketAdvice}'s style in this same package (ported from
 * restaurant-service's {@code PromotionAdvice} via carwash-service's identical port).
 *
 * <ul>
 *   <li>{@link PromoRuleValidationException} — a type/parameter mismatch on rule/coupon CRUD, or an
 *       unknown rule/coupon id → {@code 422 Unprocessable Entity} ({@code promo-rule-invalid}).
 *   <li>{@link CouponInvalidException} — an unknown/expired/inactive coupon code, or one whose
 *       linked rule is not currently effective, supplied at checkout time → {@code 422} ({@code
 *       coupon-invalid}).
 *   <li>{@link CouponExhaustedException} — a coupon whose redemption count is exhausted (advisory
 *       or the definitive atomic-UPDATE race loss) → {@code 409 Conflict} ({@code
 *       coupon-exhausted}).
 *   <li>{@link ManualDiscountForbiddenException} — a non-owner/manager caller attempted a manual
 *       discount or a promotion admin CRUD write → {@code 403 Forbidden} ({@code
 *       manual-discount-forbidden}).
 * </ul>
 *
 * <p><strong>Port note (deliberate omission vs. restaurant's {@code PromotionAdvice} — same
 * decision as carwash-service's identical port).</strong> Restaurant's source class ALSO handles
 * {@code DataIntegrityViolationException} (a duplicate coupon {@code (company_id, code)}) —
 * restaurant has no {@code CatalogAdvice} of its own, so that was a clean addition there.
 * Barbershop's {@link CatalogAdvice} ALREADY registers a {@code DataIntegrityViolationException}
 * handler service-wide (409, generic "catalog-item-conflict" detail); declaring a SECOND handler
 * for the identical exception type here — with neither advice bean carrying an {@code @Order} —
 * would make Spring's cross-advice {@code @ExceptionHandler} resolution
 * ambiguous/bean-order-dependent for EVERY {@code DataIntegrityViolationException} in this service,
 * not just a duplicate coupon. Left unhandled here on purpose: a duplicate coupon code still
 * surfaces as {@code 409 Conflict} via {@link CatalogAdvice}'s existing catch-all (a slightly
 * generic {@code catalog-item-conflict} detail message rather than a promotion-specific one) — no
 * ambiguity, no raw {@code 500}. Flagged for the tech lead/integration-engineer as a follow-up
 * (either give {@code CatalogAdvice} a coupon-aware detail branch, or promote both handlers behind
 * a single ordered advice).
 */
@RestControllerAdvice
public class PromotionAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(PromoRuleValidationException.class)
  public ProblemDetail handlePromoRuleInvalid(
      PromoRuleValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "promo-rule-invalid"));
    problem.setTitle("Invalid promotion rule/coupon request");
    problem.setDetail(ex.getMessage());
    return decorate(problem, request);
  }

  @ExceptionHandler(CouponInvalidException.class)
  public ProblemDetail handleCouponInvalid(CouponInvalidException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    problem.setType(URI.create(TYPE_BASE + "coupon-invalid"));
    problem.setTitle("Invalid coupon");
    problem.setDetail(
        "The supplied coupon code is unknown, expired, inactive, or not currently effective.");
    problem.setProperty("code", ex.getCode());
    return decorate(problem, request);
  }

  @ExceptionHandler(CouponExhaustedException.class)
  public ProblemDetail handleCouponExhausted(
      CouponExhaustedException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    problem.setType(URI.create(TYPE_BASE + "coupon-exhausted"));
    problem.setTitle("Coupon exhausted");
    problem.setDetail("The coupon's redemption limit has been reached.");
    problem.setProperty("code", ex.getCode());
    return decorate(problem, request);
  }

  @ExceptionHandler(ManualDiscountForbiddenException.class)
  public ProblemDetail handleManualDiscountForbidden(
      ManualDiscountForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    problem.setType(URI.create(TYPE_BASE + "manual-discount-forbidden"));
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
