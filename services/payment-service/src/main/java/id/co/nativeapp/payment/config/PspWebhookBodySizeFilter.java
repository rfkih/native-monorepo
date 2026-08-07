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
    long declared = request.getContentLengthLong();
    if (declared > MAX_BODY_BYTES) {
      // 413 literal — HttpStatus.PAYLOAD_TOO_LARGE is deprecated in Framework 7 (renamed).
      response.sendError(413, "Webhook body too large");
      return;
    }
    chain.doFilter(request, response);
  }
}
