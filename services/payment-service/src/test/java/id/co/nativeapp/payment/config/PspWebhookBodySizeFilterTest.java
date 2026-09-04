package id.co.nativeapp.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link PspWebhookBodySizeFilter} — the pre-parse body cap on the ANONYMOUS Midtrans
 * webhook surface (security review W1): at most {@value PspWebhookBodySizeFilter#MAX_BODY_BYTES}
 * bytes, whether declared ({@code Content-Length}) or streamed (chunked).
 *
 * <p>Chunked MUST pass through when within the cap — THE prod outage regression: Midtrans sends
 * {@code Content-Length}, but its request reaches this service THROUGH THE GATEWAY, whose proxy
 * re-streams every forwarded body as {@code Transfer-Encoding: chunked}. The original W1 posture
 * refused chunked with 411, whose {@code sendError} dispatch to {@code /error} (not a public path)
 * was then morphed by the security chain into the bodyless 401 every Midtrans notification got —
 * settlement webhooks never worked; only the POS {@code /sync} polling fallback masked it.
 */
class PspWebhookBodySizeFilterTest {

  private final PspWebhookBodySizeFilter filter = new PspWebhookBodySizeFilter();

  @Test
  void anOversizedContentLengthIsRejectedWith413BeforeTheBodyIsRead() throws Exception {
    // Mockito mock: getContentLengthLong() claims an arbitrary size WITHOUT allocating it — the
    // declared-length fast path must reject before ever touching the stream.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong())
        .thenReturn((long) PspWebhookBodySizeFilter.MAX_BODY_BYTES + 1);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void aChunkedNotificationWithinTheCapProceedsWithItsBodyIntact() throws Exception {
    // THE regression: the gateway-forwarded (chunked, no Content-Length) Midtrans notification
    // must reach WebhookService with its body — the signature verifies the exact raw bytes.
    MockHttpServletRequest backing = new MockHttpServletRequest();
    backing.setContent(
        "{\"order_id\":\"n-x\",\"status_code\":\"200\"}".getBytes(StandardCharsets.UTF_8));
    backing.addHeader("Transfer-Encoding", "chunked");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> downstreamBody = new AtomicReference<>();

    filter.doFilter(withoutContentLength(backing), response, chainReadingBodyInto(downstreamBody));

    assertThat(downstreamBody).hasValue("{\"order_id\":\"n-x\",\"status_code\":\"200\"}");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aDeclaredNotificationWithinTheCapProceedsWithItsBodyIntact() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent("{\"order_id\":\"n-x\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> downstreamBody = new AtomicReference<>();

    filter.doFilter(request, response, chainReadingBodyInto(downstreamBody));

    assertThat(downstreamBody).hasValue("{\"order_id\":\"n-x\"}");
  }

  @Test
  void aStreamedBodyExceedingTheCapIsRejectedWith413() throws Exception {
    // Chunked (undeclared length) over the ceiling: the cap binds while reading — the heap-DoS
    // the filter exists to stop, now enforced instead of dodged by refusing chunked outright.
    MockHttpServletRequest backing = new MockHttpServletRequest();
    backing.setContent(new byte[PspWebhookBodySizeFilter.MAX_BODY_BYTES + 1]);
    backing.addHeader("Transfer-Encoding", "chunked");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(withoutContentLength(backing), response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void aLyingSmallContentLengthCannotSlipAnOversizedStreamPastTheCap() throws Exception {
    // Declared 10 bytes, streamed cap+1: the fast path passes, but the cap binds on bytes READ.
    MockHttpServletRequest backing = new MockHttpServletRequest();
    backing.setContent(new byte[PspWebhookBodySizeFilter.MAX_BODY_BYTES + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        new HttpServletRequestWrapper(backing) {
          @Override
          public long getContentLengthLong() {
            return 10L;
          }
        },
        response,
        chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void theBufferedBodyIsAlsoReplayedThroughGetReader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent("{\"order_id\":\"n-x\"}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> downstreamBody = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (req, res) -> {
          try (BufferedReader reader = req.getReader()) {
            downstreamBody.set(reader.readLine());
          } catch (java.io.IOException e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(downstreamBody).hasValue("{\"order_id\":\"n-x\"}");
  }

  @Test
  void aBodyExactlyAtTheCeilingProceedsThroughTheChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[PspWebhookBodySizeFilter.MAX_BODY_BYTES]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  @Test
  void aRejectionIsWrittenDirectlyNeverViaSendError() throws Exception {
    // sendError ERROR-dispatches to /error — not in native.security.public-paths, so the security
    // chain would answer a bodyless 401 (what Midtrans saw for every notification). Direct write
    // keeps the true 413 on the wire.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong())
        .thenReturn((long) PspWebhookBodySizeFilter.MAX_BODY_BYTES + 1);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chainThatSets(new AtomicBoolean()));

    assertThat(response.getErrorMessage()).isNull(); // sendError() would have set this
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).contains("413");
  }

  /** Hides the derived Content-Length so the request presents as a chunked (undeclared) body. */
  private static HttpServletRequest withoutContentLength(MockHttpServletRequest backing) {
    return new HttpServletRequestWrapper(backing) {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1L;
      }
    };
  }

  private FilterChain chainThatSets(AtomicBoolean flag) {
    return (req, res) -> flag.set(true);
  }

  private FilterChain chainReadingBodyInto(AtomicReference<String> target) {
    return (req, res) -> {
      try {
        target.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    };
  }
}
