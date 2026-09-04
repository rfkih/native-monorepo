package id.co.nativeapp.restaurant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * edge. Registered ONLY for {@code /api/v1/self-order/*} (see {@link SelfOrderFilterConfig}), so
 * the authenticated POS paths are untouched. A ≤100-line cart is a few KB, so the 256 KB ceiling
 * never inconveniences a real diner.
 *
 * <p><strong>Chunked is the NORMAL wire shape here, not an anomaly.</strong> The diner's browser
 * sends {@code Content-Length}, but the request reaches this service THROUGH THE GATEWAY, whose
 * proxy re-streams every forwarded body as {@code Transfer-Encoding: chunked}. The original O-2
 * posture ("refuse chunked with 411 — fetch() always declares a length") therefore rejected EVERY
 * real order submission, and the {@code sendError} dispatch to {@code /error} (not a public path)
 * was then morphed by the security chain into a bodyless 401. The cap is now enforced WHILE
 * reading: heap exposure is bounded at O(cap) per request (≤ {@value #MAX_BODY_BYTES} buffered plus
 * a transient copy), then the buffered body is replayed to the chain. Ordered AFTER {@link
 * SelfOrderTokenFilter} (see {@link SelfOrderFilterConfig}) so only token-validated requests pay
 * the buffering.
 *
 * <p>Rejections are WRITTEN DIRECTLY, never via {@code sendError}: {@code sendError} triggers the
 * container's ERROR dispatch to {@code /error}, which the security chain answers with a bodyless
 * 401 in place of the real status.
 */
public class SelfOrderBodySizeFilter extends OncePerRequestFilter {

  /** Max anonymous self-order request body, bytes. Generous for a ≤100-line cart, DoS-safe. */
  static final long MAX_BODY_BYTES = 256L * 1024L;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Fast path: a declared length over the cap is refused without reading a single body byte.
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      reject(response, request.getRequestURI());
      return;
    }
    // Declared-within-cap, undeclared (-1), and chunked alike: buffer at most the cap — a lying
    // Content-Length cannot slip the cap either, because the cap binds on the bytes READ.
    byte[] body = readAtMost(request.getInputStream(), (int) MAX_BODY_BYTES);
    if (body == null) {
      reject(response, request.getRequestURI());
      return;
    }
    chain.doFilter(new BufferedBodyRequest(request, body), response);
  }

  private static void reject(HttpServletResponse response, String instance) throws IOException {
    // 413 literal — HttpStatus.PAYLOAD_TOO_LARGE is deprecated in Framework 7 (renamed). The
    // instance is the servlet's UNDECODED request URI (never caller-decoded content).
    response.setStatus(413);
    response.setContentType("application/problem+json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,"
                + "\"detail\":\"Self-order body exceeds 256 KB\",\"instance\":\""
                + instance
                + "\"}");
  }

  /**
   * The stream's first {@code max} bytes, or {@code null} the moment byte {@code max+1} arrives.
   */
  private static byte[] readAtMost(InputStream in, int max) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(max, 8 * 1024));
    byte[] chunk = new byte[8 * 1024];
    int total = 0;
    int read;
    while ((read = in.read(chunk)) != -1) {
      total += read;
      if (total > max) {
        return null;
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /** Replays the already-capped buffered body to the chain (the container stream is consumed). */
  static final class BufferedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    BufferedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream delegate = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Non-blocking read is not supported");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }
}
