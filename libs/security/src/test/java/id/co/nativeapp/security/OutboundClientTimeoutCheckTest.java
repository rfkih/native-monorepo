package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The §4 outbound-client timeout startup self-check: a non-positive JWKS timeout must fail fast at
 * boot, not surface later as a hung request. Pure unit test — no Spring context, no container.
 */
class OutboundClientTimeoutCheckTest {

  @Test
  void passesWithThePositiveDefaults() {
    JwksClientProperties props = new JwksClientProperties();
    assertThatCode(() -> new OutboundClientTimeoutCheck(props).afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void failsFastOnAZeroConnectTimeout() {
    JwksClientProperties props = new JwksClientProperties();
    props.setConnectTimeout(Duration.ZERO);
    assertThatThrownBy(() -> new OutboundClientTimeoutCheck(props).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("native.security.jwks.connect-timeout")
        .hasMessageContaining("positive");
  }

  @Test
  void failsFastOnANegativeReadTimeout() {
    JwksClientProperties props = new JwksClientProperties();
    props.setReadTimeout(Duration.ofSeconds(-1));
    assertThatThrownBy(() -> new OutboundClientTimeoutCheck(props).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("native.security.jwks.read-timeout");
  }

  @Test
  void failsFastOnANullTimeout() {
    JwksClientProperties props = new JwksClientProperties();
    props.setConnectTimeout(null);
    assertThatThrownBy(() -> new OutboundClientTimeoutCheck(props).afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class);
  }
}
