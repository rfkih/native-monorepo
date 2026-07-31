package id.co.nativeapp.restaurant.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test for {@link SelfOrderBodySizeFilter} — the container-edge body-size guard for the
 * ANONYMOUS self-order surface (security review F-2): a {@code Content-Length} over {@value
 * SelfOrderBodySizeFilter#MAX_BODY_BYTES} bytes is refused {@code 413} before the chain (and
 * therefore Jackson) ever sees the body.
 *
 * <p>No Spring context — a plain unit-level {@code doFilter} invocation, mirroring {@code
 * selforder.SelfOrderTokenFilterTest}'s style (direct filter construction + {@link
 * MockHttpServletRequest}/{@link MockHttpServletResponse} + an {@link AtomicBoolean} flag proving
 * whether the chain ran).
 */
class SelfOrderBodySizeFilterTest {

  private final SelfOrderBodySizeFilter filter = new SelfOrderBodySizeFilter();

  @Test
  void anOversizedContentLengthIsRejectedWith413BeforeTheChainRuns() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/self-order/orders");
    request.setContentLength((int) (SelfOrderBodySizeFilter.MAX_BODY_BYTES + 1));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE.value());
  }

  @Test
  void aNormalSizedBodyProceedsThroughTheChain() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/self-order/orders");
    request.setContentLength(1_024);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void aBodyExactlyAtTheCeilingProceedsThroughTheChain() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/self-order/orders");
    request.setContentLength((int) SelfOrderBodySizeFilter.MAX_BODY_BYTES);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  @Test
  void noContentLengthHeaderProceedsThroughTheChain() throws Exception {
    // getContentLengthLong() returns -1 when unset — must never be treated as oversized.
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/self-order/orders");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
  }

  private FilterChain chainThatSets(AtomicBoolean flag) {
    return (req, res) -> flag.set(true);
  }
}
