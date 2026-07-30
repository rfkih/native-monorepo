package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.restaurant.promotion.domain.CouponInvalidException;
import id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.restaurant.promotion.domain.PromoRuleValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The promotion-feature-specific RFC 7807 {@link ProblemDetail} advice — restaurant has no {@code
 * CatalogAdvice} of its own (that class lives in carwash), so this mirrors ITS style: narrow,
 * feature- scoped handlers ordered ahead of the shared {@code libs/security ApiExceptionHandler}
 * catch-all.
 *
 * <ul>
 *   <li>{@link PromoRuleValidationException} — a type/parameter mismatch on rule/coupon CRUD, or an
 *       unknown rule/coupon id → {@code 422 Unprocessable Entity} ({@code promo-rule-invalid}).
 *   <li>{@link CouponInvalidException} — an unknown/expired/inactive coupon code, or one whose
 *       linked rule is not currently effective, supplied at checkout/pay time → {@code 422} ({@code
 *       coupon-invalid}).
 *   <li>{@link CouponExhaustedException} — a coupon whose redemption count is exhausted (advisory
 *       or the definitive atomic-UPDATE race loss) → {@code 409 Conflict} ({@code
 *       coupon-exhausted}).
 *   <li>{@link ManualDiscountForbiddenException} — a non-owner/manager caller attempted a manual
 *       discount or a promotion admin CRUD write → {@code 403 Forbidden} ({@code
 *       manual-discount-forbidden}).
 *   <li>{@link DataIntegrityViolationException} — most notably a duplicate {@code coupon} {@code
 *       (company_id, code)} → {@code 409}, rather than leaking a raw {@code 500}.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PromotionAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(PromoRuleValidationException.class)
  public ProblemDetail handlePromoRuleInvalid(
      PromoRuleValidationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "promo-rule-invalid", request);
    problem.setTitle("Invalid promotion rule/coupon request");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(CouponInvalidException.class)
  public ProblemDetail handleCouponInvalid(CouponInvalidException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "coupon-invalid", request);
    problem.setTitle("Invalid coupon");
    problem.setDetail(
        "The supplied coupon code is unknown, expired, inactive, or not currently effective.");
    problem.setProperty("code", ex.getCode());
    return problem;
  }

  @ExceptionHandler(CouponExhaustedException.class)
  public ProblemDetail handleCouponExhausted(
      CouponExhaustedException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "coupon-exhausted", request);
    problem.setTitle("Coupon exhausted");
    problem.setDetail("The coupon's redemption limit has been reached.");
    problem.setProperty("code", ex.getCode());
    return problem;
  }

  @ExceptionHandler(ManualDiscountForbiddenException.class)
  public ProblemDetail handleManualDiscountForbidden(
      ManualDiscountForbiddenException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "manual-discount-forbidden", request);
    problem.setTitle("Forbidden");
    problem.setDetail(ex.getMessage());
    return problem;
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleConflict(
      DataIntegrityViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "promotion-conflict", request);
    problem.setTitle("Conflict");
    problem.setDetail(
        "The operation conflicted with an existing row (e.g. a duplicate coupon code).");
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String slug, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(TYPE_BASE + slug));
    problem.setInstance(URI.create(request.getRequestURI()));
    String traceId = MDC.get("traceId");
    if (traceId != null) {
      problem.setProperty("traceId", traceId);
    }
    return problem;
  }
}
