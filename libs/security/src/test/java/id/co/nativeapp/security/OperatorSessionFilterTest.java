package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The {@link OperatorSessionFilter} matrix (ADR 0049 P2) — pure unit tests (no Spring context, no
 * container), mirroring restaurant-service's {@code SelfOrderTokenFilterTest} style but exercising
 * this filter's materially different "absent is the norm, present-and-invalid is rejected" posture
 * (see the filter's class javadoc).
 */
class OperatorSessionFilterTest {

  private static final byte[] SECRET =
      "a-32-byte-test-operator-signing-key".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_SECRET =
      "a-different-32-byte-signing-key!!!!".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final String COMPANY_ID = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID EMPLOYEE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static OperatorTokenPayload validPayload() {
    return new OperatorTokenPayload(
        OperatorTokenPayload.CURRENT_VERSION,
        COMPANY_ID,
        BUSINESS_ID,
        "keycloak-sub-operator-1",
        EMPLOYEE_ID,
        "Budi Santoso",
        "cashier",
        NOW.minusSeconds(60).getEpochSecond(),
        NOW.plusSeconds(3600).getEpochSecond(),
        UUID.randomUUID().toString());
  }

  private static FilterChain chainThatSets(AtomicBoolean flag) {
    return (req, res) -> flag.set(true);
  }

  @Test
  void noHeaderPresentContinuesTheChainWithoutBindingAPrincipal() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
    assertThat(request.getAttribute(OperatorPrincipal.REQUEST_ATTRIBUTE)).isNull();
    // No error status was ever written — MockHttpServletResponse defaults to 200.
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void aBlankHeaderIsTreatedAsAbsent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
    assertThat(request.getAttribute(OperatorPrincipal.REQUEST_ATTRIBUTE)).isNull();
  }

  @Test
  void aMalformedTokenIs401AndTheChainIsNeverReached() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, "not-a-real-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(response.getContentType()).startsWith("application/problem+json");
  }

  @Test
  void aTamperedSignatureIs401AndTheChainIsNeverReached() throws Exception {
    String token = OperatorTokenCodec.encode(validPayload(), SECRET);
    // Valid signature, but for a DIFFERENT key — the verifier must reject it.
    String forged = OperatorTokenCodec.encode(validPayload(), OTHER_SECRET);
    assertThat(forged).isNotEqualTo(token); // sanity: different bytes

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, forged);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    // Verifying against SECRET, the token forged with OTHER_SECRET must fail.
    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void anExpiredTokenIs401AndTheChainIsNeverReached() throws Exception {
    OperatorTokenPayload expired =
        new OperatorTokenPayload(
            OperatorTokenPayload.CURRENT_VERSION,
            COMPANY_ID,
            BUSINESS_ID,
            "keycloak-sub-operator-1",
            EMPLOYEE_ID,
            "Budi Santoso",
            "cashier",
            NOW.minusSeconds(7200).getEpochSecond(),
            NOW.minusSeconds(60).getEpochSecond(), // exp in the past relative to FIXED_CLOCK
            UUID.randomUUID().toString());
    String token = OperatorTokenCodec.encode(expired, SECRET);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void aTokenExpiringExactlyNowIsRejected() throws Exception {
    // exp == "now" (epoch seconds) must be treated as already expired (the filter checks
    // `exp <= now`, not `exp < now`) — a token's validity window is exclusive of its own exp.
    OperatorTokenPayload payload =
        new OperatorTokenPayload(
            OperatorTokenPayload.CURRENT_VERSION,
            COMPANY_ID,
            BUSINESS_ID,
            "keycloak-sub-operator-1",
            EMPLOYEE_ID,
            "Budi Santoso",
            "cashier",
            NOW.minusSeconds(60).getEpochSecond(),
            NOW.getEpochSecond(),
            UUID.randomUUID().toString());
    String token = OperatorTokenCodec.encode(payload, SECRET);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void aValidTokenBindsTheOperatorPrincipalAndReachesTheChain() throws Exception {
    OperatorTokenPayload payload = validPayload();
    String token = OperatorTokenCodec.encode(payload, SECRET);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    new OperatorSessionFilter(SECRET, FIXED_CLOCK)
        .doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    OperatorPrincipal principal =
        (OperatorPrincipal) request.getAttribute(OperatorPrincipal.REQUEST_ATTRIBUTE);
    assertThat(principal).isNotNull();
    assertThat(principal.companyId()).isEqualTo(payload.companyId());
    assertThat(principal.businessId()).isEqualTo(payload.businessId());
    assertThat(principal.operatorUserId()).isEqualTo(payload.operatorUserId());
    assertThat(principal.operatorEmployeeId()).isEqualTo(payload.operatorEmployeeId());
    assertThat(principal.role()).isEqualTo(payload.role());
  }
}
