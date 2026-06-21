package id.co.nativeapp.gateway.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The gateway's JWKS-fetch timeouts (§4) must be positive: the constructor fails fast on a
 * zero/negative connect or read timeout so the auth edge can never be wired with an infinite
 * timeout. Pure unit test — no Spring context, no container.
 */
class JwtDecoderConfigTest {

  @Test
  void acceptsPositiveJwksTimeouts() {
    assertThatCode(
            () ->
                new JwtDecoderConfig(
                    Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(3)))
        .doesNotThrowAnyException();
  }

  @Test
  void failsFastOnAZeroJwksConnectTimeout() {
    assertThatThrownBy(
            () -> new JwtDecoderConfig(Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("native.gateway.jwt.jwks-connect-timeout");
  }

  @Test
  void failsFastOnANegativeJwksReadTimeout() {
    assertThatThrownBy(
            () ->
                new JwtDecoderConfig(
                    Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("native.gateway.jwt.jwks-read-timeout");
  }
}
