package id.co.nativeapp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Verifies the OPTIONAL {@code X-Operator-Session} header OFFLINE (ADR 0049 P2) — a shared filter
 * every vertical (restaurant today; carwash/barbershop from P4) registers over its own POS write
 * routes, mirroring restaurant-service's {@code SelfOrderTokenFilter} for the token
 * decode/verify/reject mechanics, but with a materially different trust posture:
 *
 * <ul>
 *   <li><strong>The header is OPTIONAL, not the sole gate.</strong> {@code SelfOrderTokenFilter}
 *       authenticates a fully anonymous surface (no token → 401). Here the caller already carries a
 *       normal person/device JWT (validated upstream by the gateway / {@code TenantBindingFilter});
 *       an operator session merely says WHO is ringing this specific sale. So a MISSING or BLANK
 *       header is the norm today (no PIN operators exist yet in the field) — the filter runs {@code
 *       filterChain.doFilter} unchanged, WITHOUT setting an {@link OperatorPrincipal}, and the
 *       consumer falls back to the actor (unchanged pre-ADR-0049 behaviour).
 *   <li><strong>Only a PRESENT, malformed/unverifiable/expired header is rejected.</strong> A
 *       caller that bothers to send the header is asserting an operator identity; if that assertion
 *       cannot be verified, the request is rejected outright (uniform {@code 401}) rather than
 *       silently degrading to "no operator" — a forged or stale token must never be treated as
 *       absent (that would let a stripped/garbled header quietly fall back to attributing the
 *       DEVICE actor instead of failing loudly).
 *   <li><strong>No {@code TenantContext} interaction.</strong> This filter does not read or bind
 *       {@link id.co.nativeapp.tenant.TenantContext} — company/outlet matching against the ALREADY
 *       bound tenant is the CONSUMER's job (e.g. restaurant-service's {@code SaleWriter}), never
 *       this filter's. This sidesteps the fragile cross-profile filter-ordering problem that {@code
 *       DevTenantFilter} (dev, registered very early) vs. {@code TenantBindingFilter} (non-dev,
 *       registered inside the Spring Security chain) would otherwise create — this filter can be
 *       registered at any sane order relative to either.
 * </ul>
 *
 * <p>ANY failure once the header is present — malformed token, bad signature, or an expired {@code
 * exp} — is mapped to a UNIFORM {@code 401} RFC-7807 {@link org.springframework.http.ProblemDetail}
 * body, written directly (this filter runs before MVC content negotiation, so no
 * {@code @RestControllerAdvice} can catch anything thrown here): the response never reveals WHICH
 * failure mode was hit, so a probing attacker cannot use it as an oracle.
 */
public class OperatorSessionFilter extends OncePerRequestFilter {

  /** The header carrying the compact {@code payload.signature} operator-session token. */
  public static final String OPERATOR_SESSION_HEADER = "X-Operator-Session";

  /** Stable RFC-7807 problem type for any operator-session token rejection. */
  static final String INVALID_TOKEN_TYPE =
      "https://errors.nativeapp.id/operator-session-token-invalid";

  private final byte[] signingKeyBytes;
  private final Clock clock;

  /**
   * @param signingKeyBytes the raw HMAC signing-key bytes (the decoded {@code
   *     native.operator-token.signing-key}) — a defensive copy is taken so the caller cannot mutate
   *     the shared key in place after construction
   * @param clock the clock the {@code exp} check runs against — injected (not {@link
   *     Clock#systemUTC()} called directly) so a test can pin "now" deterministically
   */
  public OperatorSessionFilter(byte[] signingKeyBytes, Clock clock) {
    this.signingKeyBytes = Objects.requireNonNull(signingKeyBytes, "signingKeyBytes").clone();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String token = request.getHeader(OPERATOR_SESSION_HEADER);
    if (token == null || token.isBlank()) {
      // The norm today: no operator session presented -> no OperatorPrincipal bound, the consumer
      // falls back to the actor exactly as before ADR 0049 (inert by default).
      chain.doFilter(request, response);
      return;
    }

    OperatorTokenPayload payload;
    try {
      payload = OperatorTokenCodec.decodePayloadUnverified(token);
    } catch (RuntimeException malformed) {
      writeUnauthorized(request, response);
      return;
    }

    if (!OperatorTokenCodec.verifySignature(token, signingKeyBytes)) {
      writeUnauthorized(request, response);
      return;
    }

    if (payload.exp() <= clock.instant().getEpochSecond()) {
      writeUnauthorized(request, response);
      return;
    }

    request.setAttribute(
        OperatorPrincipal.REQUEST_ATTRIBUTE,
        new OperatorPrincipal(
            payload.companyId(),
            payload.businessId(),
            payload.operatorUserId(),
            payload.operatorEmployeeId(),
            payload.role()));

    chain.doFilter(request, response);
  }

  /**
   * Writes a uniform {@code 401} RFC-7807 {@code application/problem+json} body directly — this
   * filter runs before MVC content negotiation, so no {@code @RestControllerAdvice} can map an
   * exception thrown here (mirrors {@code libs.security.TenantBindingFilter} / restaurant-service's
   * {@code SelfOrderTokenFilter} raw-JSON pattern).
   */
  private static void writeUnauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\""
                + INVALID_TOKEN_TYPE
                + "\",\"title\":\"Invalid operator session\",\"status\":401,"
                + "\"detail\":\"The operator session token is missing, malformed, or does not"
                + " verify.\",\"instance\":\""
                + escapeJson(request.getRequestURI())
                + "\"}");
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
