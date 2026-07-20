package id.co.nativeapp.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Startup self-check for outbound-client timeouts (ENGINEERING-STANDARDS §4: "a startup self-check
 * fails fast if any registered client has a null connect or read timeout"). Runs during context
 * refresh in {@link #afterPropertiesSet()} — before the service accepts traffic — so a
 * misconfiguration is caught at boot rather than surfacing later as a hung request in production.
 *
 * <p>Today it guards the JWKS fetch timeouts ({@link JwksClientProperties}). {@code @NotNull} on
 * the properties already rejects a <em>missing</em> value; this guard additionally rejects a
 * present-but-<em>non-positive</em> ({@code 0s} = infinite, or negative) value — the genuinely
 * dangerous misconfiguration a plain {@code @NotNull} would let through — and logs the effective
 * timeouts so they are visible in ops. A new outbound client registers its validated timeout
 * properties and adds one line here, keeping the "no infinite-timeout client" rule in one place.
 */
public class OutboundClientTimeoutCheck implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(OutboundClientTimeoutCheck.class);

  private final JwksClientProperties jwks;

  public OutboundClientTimeoutCheck(JwksClientProperties jwks) {
    this.jwks = jwks;
  }

  @Override
  public void afterPropertiesSet() {
    requirePositive("native.security.jwks.connect-timeout", jwks.getConnectTimeout());
    requirePositive("native.security.jwks.read-timeout", jwks.getReadTimeout());
    log.info(
        "outbound-client timeout self-check passed — JWKS connect={} read={}",
        jwks.getConnectTimeout(),
        jwks.getReadTimeout());
  }

  /**
   * Fails fast if {@code value} is null, zero, or negative — an outbound client must never run with
   * an infinite/zero timeout. Public so callers outside {@code libs/security} (e.g. a feature that
   * wires its own outbound client) can reuse the same check rather than duplicating the logic.
   */
  public static void requirePositive(String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(
          name
              + " must be a positive duration — an outbound client must never use an infinite/zero"
              + " timeout (ENGINEERING-STANDARDS §4); got "
              + value);
    }
  }
}
