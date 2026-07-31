package id.co.nativeapp.restaurant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link SelfOrderBodySizeFilter} — the container-edge body-size guard for the
 * ANONYMOUS self-order surface (security review F-2): a {@code Content-Length} over {@value
 * SelfOrderBodySizeFilter#MAX_BODY_BYTES} bytes is refused {@code 413} before the chain (and
 * therefore Jackson) ever sees the body.
 *
 * <p>No Spring context. The request is a Mockito mock so {@code getContentLengthLong()} can be
 * stubbed to an arbitrary value WITHOUT allocating a body of that size (the very thing the filter
 * exists to prevent) — {@code MockHttpServletRequest} would force a real {@code setContent(byte[])}.
 */
class SelfOrderBodySizeFilterTest {

  private final SelfOrderBodySizeFilter filter = new SelfOrderBodySizeFilter();

  @Test
  void anOversizedContentLengthIsRejectedWith413BeforeTheChainRuns() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        requestWithLength(SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1),
        response,
        chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void aNormalSizedBodyProceedsThroughTheChain() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(requestWithLength(1_024), response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aBodyExactlyAtTheCeilingProceedsThroughTheChain() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        requestWithLength(SelfOrderBodySizeFilter.MAX_BODY_BYTES),
        response,
        chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  @Test
  void noContentLengthHeaderProceedsThroughTheChain() throws Exception {
    // getContentLengthLong() returns -1 when unset — must never be treated as oversized.
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(requestWithLength(-1L), response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  private HttpServletRequest requestWithLength(long contentLength) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn(contentLength);
    return request;
  }

  private FilterChain chainThatSets(AtomicBoolean flag) {
    return (req, res) -> flag.set(true);
  }
}
