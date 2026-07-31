package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenCodec;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenPayload;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessReader;
import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the ANONYMOUS self-order surface (Phase 6, ADR 0029): registered ONLY for {@code
 * /api/v1/self-order/**} (see {@link SelfOrderFilterConfig}), it is the sole gate on that path —
 * there is no JWT, no gateway session, nothing but a per-outlet HMAC token in the {@value
 * #TOKEN_HEADER} header.
 *
 * <p><strong>The tenant-free-lookup problem, and the recipe this filter follows.</strong> Every
 * OTHER read/write in this service is RLS-scoped to an ALREADY-bound tenant (from a validated JWT or
 * the dev header). Here there is no tenant yet — the caller is anonymous — so the very first lookup
 * ("does this {@code (outletId, kid)} pair name a real, ACTIVE secret?") has no tenant to scope
 * itself by. A genuinely tenant-unbound probe (a second, unscoped DB role, or a Postgres SECURITY
 * DEFINER function) would need its own connection pool and its own hole punched through RLS — a
 * bigger, riskier surface than this feature warrants. Instead: <strong>parse {@code companyId} from
 * the token's UNVERIFIED payload FIRST, bind {@link TenantContext} to it with a placeholder actor,
 * THEN run the {@code (outletId, kid)} lookup RLS-scoped to that claimed tenant.</strong> RLS itself
 * closes the gap this opens: if the claimed {@code companyId} is wrong (forged, or simply names a
 * company that does not own that outlet/kid), the real row — owned by a DIFFERENT tenant — is
 * invisible under the wrong scope, so the lookup finds nothing and the request is rejected. Only
 * once a row IS found does the HMAC signature get checked (constant-time) against that row's
 * decrypted secret, so the final tenant binding used for the rest of the request is always the row's
 * OWN {@code company_id} — proven consistent by the RLS-scoped read that found it, never trusted
 * from the payload standing alone.
 *
 * <p>On success, the actor bound for the remainder of the request is {@code self-order:<table>} (or
 * {@code self-order:kiosk} for a kiosk token) — it lands in every {@code Auditable created_by}/{@code
 * updated_by} the anonymous create writes, and {@link SelfOrderPrincipal} (the outlet/table
 * context) is set as a request attribute for {@code selforder.service.SelfOrderService} to read.
 *
 * <p>ANY failure — missing header, malformed token, unknown/retired {@code kid}, cross-outlet/
 * cross-tenant claim, or a bad signature — is mapped to a UNIFORM {@code 401} RFC-7807 {@link
 * org.springframework.http.ProblemDetail} body, written directly (mirroring {@code
 * libs.security.TenantBindingFilter}'s raw-JSON pattern — a filter runs before MVC content
 * negotiation, so no {@code @RestControllerAdvice} can catch anything thrown here): the response
 * never reveals WHICH failure mode was hit, so a probing attacker cannot use it as an oracle.
 */
public class SelfOrderTokenFilter extends OncePerRequestFilter {

  /** The header carrying the compact {@code payload.signature} self-order token. */
  public static final String TOKEN_HEADER = "X-Self-Order-Token";

  /** Stable RFC-7807 problem type for any self-order token rejection. */
  static final String INVALID_TOKEN_TYPE = "https://errors.nativeapp.id/self-order-token-invalid";

  /** The placeholder actor bound only for the RLS-scoped verification lookup (never persisted). */
  private static final String PENDING_ACTOR = "self-order:pending";

  private final SelfOrderAccessReader reader;

  public SelfOrderTokenFilter(SelfOrderAccessReader reader) {
    this.reader = reader;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String token = request.getHeader(TOKEN_HEADER);
    if (token == null || token.isBlank()) {
      writeUnauthorized(request, response);
      return;
    }

    SelfOrderTokenPayload payload;
    try {
      payload = SelfOrderTokenCodec.decodePayloadUnverified(token);
    } catch (RuntimeException malformed) {
      writeUnauthorized(request, response);
      return;
    }

    Optional<SelfOrderAccess> verified = verifyUnderTentativeTenant(payload, token);
    if (verified.isEmpty()) {
      writeUnauthorized(request, response);
      return;
    }

    // The row's OWN company_id (proven, via the RLS-scoped lookup above, to be the row's real
    // owner) — never re-derived from the unverified payload alone.
    String companyId = verified.get().getCompanyId();
    String actor = "self-order:" + (payload.tableLabel() == null ? "kiosk" : payload.tableLabel());
    request.setAttribute(
        SelfOrderPrincipal.REQUEST_ATTRIBUTE,
        new SelfOrderPrincipal(payload.businessId(), payload.outletId(), payload.tableLabel()));

    try {
      TenantContext.callAs(
          companyId,
          actor,
          () -> {
            chain.doFilter(request, response);
            return null;
          });
    } catch (ServletException | IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; the chain only throws the three re-thrown above, so this
      // is unreachable in practice.
      throw new ServletException(e);
    }
  }

  /**
   * Runs the RLS-scoped {@code (outletId, kid)} + signature verification under a TENTATIVE tenant
   * binding taken from the token's unverified {@code companyId} claim (see class javadoc for why
   * this is safe: RLS makes a wrong claim resolve to nothing).
   */
  private Optional<SelfOrderAccess> verifyUnderTentativeTenant(
      SelfOrderTokenPayload payload, String token) {
    try {
      return TenantContext.callAs(
          payload.companyId(),
          PENDING_ACTOR,
          () -> reader.verify(payload.outletId(), payload.kid(), token));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; reader.verify throws only unchecked, so this is
      // unreachable in practice.
      throw new IllegalStateException("Unexpected checked exception verifying a self-order token", e);
    }
  }

  /**
   * Writes a uniform {@code 401} RFC-7807 {@code application/problem+json} body directly — this
   * filter runs before MVC content negotiation, so no {@code @RestControllerAdvice} can map an
   * exception thrown here (mirrors {@code libs.security.TenantBindingFilter}'s raw-JSON pattern).
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
                + "\",\"title\":\"Invalid self-order token\",\"status\":401,"
                + "\"detail\":\"The self-order token is missing, malformed, or does not verify.\","
                + "\"instance\":\""
                + escapeJson(request.getRequestURI())
                + "\"}");
  }

  private static String escapeJson(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
