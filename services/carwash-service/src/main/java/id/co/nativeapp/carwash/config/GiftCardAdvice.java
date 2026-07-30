package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.giftcard.domain.GiftCardMintLimitExceededException;
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
 * Maps the gift-card SELL mint-ceiling fault (security review W-3) to a {@code 422 Unprocessable
 * Entity} RFC-7807 {@link ProblemDetail} response. Mirrors {@code PromotionAdvice}/{@code
 * LoyaltyRefAdvice}'s narrow, feature-scoped style.
 *
 * <ul>
 *   <li>{@link GiftCardMintLimitExceededException} — a SELL request's {@code amountMinor} exceeds
 *       {@code native.giftcard.max-mint-minor} → {@code 422} ({@code gift-card-mint-limit}).
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GiftCardAdvice {

  private static final String TYPE_BASE = "https://errors.nativeapp.id/";

  @ExceptionHandler(GiftCardMintLimitExceededException.class)
  public ProblemDetail handleGiftCardMintLimitExceeded(
      GiftCardMintLimitExceededException ex, HttpServletRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "gift-card-mint-limit", request);
    problem.setTitle("Gift-card mint amount exceeds the configured limit");
    problem.setDetail(ex.getMessage());
    problem.setProperty("requestedMinor", ex.getRequestedMinor());
    problem.setProperty("limitMinor", ex.getLimitMinor());
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
