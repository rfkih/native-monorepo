package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.loyaltyref.domain.GiftCardUnusableException;
import id.co.nativeapp.restaurant.loyaltyref.domain.LoyaltyBalanceInsufficientException;
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
 * Maps the two ADR 0027 (Phase 4, loyalty + gift cards) checkout-time redemption faults to {@code
 * 409 Conflict} RFC-7807 {@link ProblemDetail} responses. Mirrors {@code PromotionAdvice}'s
 * narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link LoyaltyBalanceInsufficientException} — the requested points redemption could not be
 *       atomically decremented from the local {@code member_balance_ref} cache (unknown member, or
 *       a lost race) → {@code 409} ({@code loyalty-balance-insufficient}).
 *   <li>{@link GiftCardUnusableException} — the requested gift-card redemption could not be
 *       atomically decremented from the local {@code gift_card_ref} cache (unknown card, not
 *       ACTIVE, currency mismatch, or a lost race) → {@code 409} ({@code gift-card-unusable}).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoyaltyRefAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(LoyaltyBalanceInsufficientException.class)
  public ProblemDetail handleLoyaltyBalanceInsufficient(
      LoyaltyBalanceInsufficientException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "loyalty-balance-insufficient", request);
    problem.setTitle("Loyalty balance insufficient");
    problem.setDetail(
        "The loyalty member is unknown to this outlet, or its points balance does not cover the"
            + " requested redemption.");
    problem.setProperty("memberId", ex.getMemberId().toString());
    return problem;
  }

  @ExceptionHandler(GiftCardUnusableException.class)
  public ProblemDetail handleGiftCardUnusable(
      GiftCardUnusableException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "gift-card-unusable", request);
    problem.setTitle("Gift card unusable");
    problem.setDetail(
        "The gift card is unknown to this outlet, not active, currency-mismatched, or its balance"
            + " does not cover the requested redemption.");
    problem.setProperty("giftCardId", ex.getGiftCardId().toString());
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
