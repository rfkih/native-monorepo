package id.co.nativeapp.payment.config;

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
 * Pre-parse body cap for the ANONYMOUS PSP-webhook surface (the ADR 0029 {@code
 * SelfOrderBodySizeFilter} idiom): an unauthenticated caller must not be able to push an
 * arbitrarily large body into JSON parsing. A real Midtrans notification is under 2 KB; 64 KB is
 * generous headroom. Registered via {@link PspWebhookFilterConfig} scoped to {@code
 * /api/v1/psp-webhooks/*} only — no authenticated surface is affected.
 *
 * <p><strong>Chunked is the NORMAL wire shape here, not an anomaly.</strong> Midtrans itself sends
 * {@code Content-Length}, but its request reaches this service THROUGH THE GATEWAY, whose proxy
 * re-streams every forwarded body as {@code Transfer-Encoding: chunked}. The original W1 posture
 * ("refuse chunked with 411 — Midtrans always declares a length") therefore rejected EVERY real
 * notification, and the {@code sendError} dispatch to {@code /error} (not a public path) was then
 * morphed by the security chain into the bodyless 401 Midtrans retried against for the webhook's
 * whole life — masked only by the POS {@code /sync} polling fallback. The cap is now enforced WHILE
 * reading: heap exposure is bounded at O(cap) per request (≤ {@value #MAX_BODY_BYTES} buffered plus
 * a transient copy), then the buffered body is replayed to the chain byte-for-byte — {@code
 * WebhookService} re-derives the merchant signature from the PARSED {@code order_id}/{@code
 * status_code}/{@code gross_amount} fields, so those values (and hence the bytes they parse from)
 * must reach it unaltered.
 *
 * <p>Rejections are WRITTEN DIRECTLY, never via {@code sendError}: {@code sendError} triggers the
 * container's ERROR dispatch to {@code /error}, which is not in {@code
 * native.security.public-paths}, so the security chain answers a bodyless 401 in place of the real
 * status — exactly the outage signature this class caused once already.
 */
public class PspWebhookBodySizeFilter extends OncePerRequestFilter {

  static final int MAX_BODY_BYTES = 64 * 1024;

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
    byte[] body = readAtMost(request.getInputStream(), MAX_BODY_BYTES);
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
                + "\"detail\":\"Webhook body exceeds 64 KB\",\"instance\":\""
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
