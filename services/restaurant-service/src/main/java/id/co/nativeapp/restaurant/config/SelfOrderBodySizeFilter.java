package id.co.nativeapp.restaurant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an oversized ANONYMOUS self-order request body BEFORE Jackson materializes it (security
 * review F-2). {@code POST /api/v1/self-order/orders} is the fleet's only unauthenticated write; a
 * crafted multi-MB body of cart lines would otherwise be deserialized into a {@code List} on the
 * heap and only THEN meet the {@code @Size(max=100)} bean-validation ceiling — too late to stop a
 * deserialization-heap DoS across the gateway's 60 req/min/IP budget.
 *
 * <p>Servlet Spring MVC has no built-in max-JSON-body property (the {@code
 * spring.codec.max-in-memory-size} knob is WebFlux-only), so this filter caps it at the container
 * edge: a {@code Content-Length} over {@value #MAX_BODY_BYTES} bytes is refused {@code 413} without
 * reading the body. Registered ONLY for {@code /api/v1/self-order/*} (see {@link
 * SelfOrderFilterConfig}), so the authenticated POS paths are untouched. A ≤100-line cart is a few
 * KB, so the 256 KB ceiling never inconveniences a real diner.
 */
public class SelfOrderBodySizeFilter extends OncePerRequestFilter {

  /** Max anonymous self-order request body, bytes. Generous for a ≤100-line cart, DoS-safe. */
  static final long MAX_BODY_BYTES = 256L * 1024L;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // A chunked body has no Content-Length (getContentLengthLong() == -1), so the size cap below
    // cannot see it — Jackson would materialize the whole List before @Size fires (the very
    // heap-DoS this filter targets). A browser fetch() with a JSON body always sends
    // Content-Length, so the anonymous diner surface has no legitimate use for chunked encoding:
    // refuse it outright rather than let it slip the cap (security review O-2).
    String transferEncoding = request.getHeader("Transfer-Encoding");
    if (transferEncoding != null
        && transferEncoding.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
      response.sendError(
          HttpServletResponse.SC_LENGTH_REQUIRED, "self-order requires Content-Length");
      return;
    }
    long contentLength = request.getContentLengthLong();
    if (contentLength > MAX_BODY_BYTES) {
      response.sendError(
          HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "self-order request body too large");
      return;
    }
    chain.doFilter(request, response);
  }
}
