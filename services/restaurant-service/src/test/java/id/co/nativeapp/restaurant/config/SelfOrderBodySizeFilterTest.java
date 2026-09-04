package id.co.nativeapp.restaurant.config;

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
 * Unit test for {@link SelfOrderBodySizeFilter} — the container-edge body-size guard for the
 * ANONYMOUS self-order surface (security review F-2): the body may not exceed {@value
 * SelfOrderBodySizeFilter#MAX_BODY_BYTES} bytes, whether its size is declared ({@code
 * Content-Length}) or streamed ({@code Transfer-Encoding: chunked}).
 *
 * <p>Chunked MUST pass through when within the cap: the gateway's proxy re-streams EVERY forwarded
 * body as chunked, so a chunked-refusal (the original O-2 posture) rejected every real diner
 * submission — the self-order/PSP-webhook 411-morphs-to-401 outage. The cap is now enforced WHILE
 * reading (bounded buffer), and a rejection is written directly — {@code sendError} would dispatch
 * to {@code /error}, which is not a public path, and the security chain would morph the 413 into a
 * bodyless 401.
 */
class SelfOrderBodySizeFilterTest {

  private final SelfOrderBodySizeFilter filter = new SelfOrderBodySizeFilter();

  @Test
  void anOversizedContentLengthIsRejectedWith413BeforeTheBodyIsRead() throws Exception {
    // A Mockito mock so getContentLengthLong() can claim an arbitrary size WITHOUT allocating it —
    // the declared-length fast path must reject before ever touching the stream.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn(SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void aNormalSizedBodyProceedsWithItsBodyIntact() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent("{\"lines\":[1,2,3]}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> downstreamBody = new AtomicReference<>();

    filter.doFilter(request, response, chainReadingBodyInto(downstreamBody));

    assertThat(downstreamBody).hasValue("{\"lines\":[1,2,3]}");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aBodyExactlyAtTheCeilingProceedsThroughTheChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContent(new byte[(int) SelfOrderBodySizeFilter.MAX_BODY_BYTES]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  @Test
  void aChunkedBodyWithinTheCapProceedsWithItsBodyIntact() throws Exception {
    // THE regression (self-order outage): chunked = no Content-Length. The gateway proxy sends
    // every forwarded body this way, so it must pass — capped while reading, not refused.
    MockHttpServletRequest backing = new MockHttpServletRequest();
    backing.setContent("{\"lines\":[1]}".getBytes(StandardCharsets.UTF_8));
    backing.addHeader("Transfer-Encoding", "chunked");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> downstreamBody = new AtomicReference<>();

    filter.doFilter(withoutContentLength(backing), response, chainReadingBodyInto(downstreamBody));

    assertThat(downstreamBody).hasValue("{\"lines\":[1]}");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aStreamedBodyExceedingTheCapIsRejectedWith413() throws Exception {
    // Chunked (undeclared length) but over the ceiling: the cap binds while reading.
    MockHttpServletRequest backing = new MockHttpServletRequest();
    backing.setContent(new byte[(int) SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1]);
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
    backing.setContent(new byte[(int) SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1]);
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
    request.setContent("{\"lines\":[1]}".getBytes(StandardCharsets.UTF_8));
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

    assertThat(downstreamBody).hasValue("{\"lines\":[1]}");
  }

  @Test
  void aRejectionIsWrittenDirectlyNeverViaSendError() throws Exception {
    // sendError would ERROR-dispatch to /error — not a public path, so the security chain morphs
    // the rejection into a bodyless 401 (the webhook outage). Direct write keeps the real status.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn(SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chainThatSets(new AtomicBoolean()));

    assertThat(response.getErrorMessage()).isNull(); // sendError() would have set this
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).contains("413");
  }

  @Test
  void anEmptyBodyRequestProceedsThroughTheChain() throws Exception {
    // GETs (menu browse) ride the same /api/v1/self-order/* registration — must never be blocked.
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
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
