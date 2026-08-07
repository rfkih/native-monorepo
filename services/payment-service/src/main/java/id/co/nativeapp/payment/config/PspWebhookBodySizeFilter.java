package id.co.nativeapp.payment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pre-parse body cap for the ANONYMOUS PSP-webhook surface (the ADR 0029 {@code
 * SelfOrderBodySizeFilter} idiom): an unauthenticated caller must not be able to push an
 * arbitrarily large body into JSON parsing. A real Midtrans notification is under 2 KB; 64 KB is
 * generous headroom. Registered via {@link PspWebhookFilterConfig} scoped to {@code
 * /api/v1/psp-webhooks/*} only — no authenticated surface is affected.
 */
public class PspWebhookBodySizeFilter extends OncePerRequestFilter {

  static final int MAX_BODY_BYTES = 64 * 1024;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // A chunked body has no Content-Length (getContentLengthLong() == -1), so the size cap below
    // cannot see it — the controller would materialize the whole streamed body into a String
    // before any auth ran (the very heap-DoS this filter targets). Midtrans's notification
    // servers always send Content-Length, so the anonymous webhook surface has no legitimate use
    // for chunked encoding: refuse it outright rather than let it slip the cap (the
    // SelfOrderBodySizeFilter O-2 hardening, ported — security review W1).
    String transferEncoding = request.getHeader("Transfer-Encoding");
    if (transferEncoding != null
        && transferEncoding.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
      response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED, "Webhook requires Content-Length");
      return;
    }
    long declared = request.getContentLengthLong();
    if (declared > MAX_BODY_BYTES) {
      // 413 literal — HttpStatus.PAYLOAD_TOO_LARGE is deprecated in Framework 7 (renamed).
      response.sendError(413, "Webhook body too large");
      return;
    }
    chain.doFilter(request, response);
  }
}
