package id.co.nativeapp.gateway.config;

import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the gateway's externalized config and the stateless collaborator beans the routes use.
 *
 * <p>{@link GatewayRouteProperties} and {@link RateLimitProperties} are bound here so the app fails
 * fast at startup if a required route target or rate-limit knob is missing (ENGINEERING-STANDARDS
 * §7). The {@link Clock} is a bean so the rate limiter's time source is injectable and tests stay
 * deterministic. The {@link TenantContextHeaderFilter} is stateless, so a single shared instance is
 * reused across every route.
 */
@Configuration
@EnableConfigurationProperties({
  GatewayRouteProperties.class,
  RateLimitProperties.class,
  CorsProperties.class
})
public class GatewayConfig {

  @Bean
  Clock systemClock() {
    return Clock.systemUTC();
  }

  @Bean
  TenantContextHeaderFilter tenantContextHeaderFilter() {
    return new TenantContextHeaderFilter();
  }
}
