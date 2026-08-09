package id.co.nativeapp.barbershop.config;

import id.co.nativeapp.security.OperatorSessionFilter;
import id.co.nativeapp.security.OperatorTokenSigningKey;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared {@code libs/security} {@link OperatorSessionFilter} over
 * barbershop-service's POS write routes (ADR 0049 P4) — the vertical opt-in the filter's own
 * javadoc calls out (it is NOT auto-registered fleet-wide). Mirrors restaurant-service's identical
 * {@code OperatorSessionFilterConfig} (P2) and carwash-service's (P4); barbershop opts in only now,
 * at P4.
 *
 * <p><strong>Scope.</strong> Path-scoped to the ticket routes that quote/checkout/capture a ticket:
 * {@code /api/v1/barbershop/tickets/**} — every controller {@code TicketWriter#create}/ {@code
 * TicketCaptureWriter#capture} call is reachable from. {@link OperatorSessionFilter} no-ops on any
 * request that carries no {@code X-Operator-Session} header, so registering it here is harmless
 * even though the read-only quote endpoint never needs it.
 *
 * <p><strong>Order.</strong> This filter does not touch {@code TenantContext} (by design — see
 * {@link OperatorSessionFilter} javadoc), so it has no correctness dependency on running before or
 * after tenant binding — mirrors restaurant-service's identical reasoning.
 */
@Configuration
public class OperatorSessionFilterConfig {

  private static final String[] URL_PATTERNS = {
    "/api/v1/barbershop/tickets", "/api/v1/barbershop/tickets/*"
  };

  /** A sane, documented order — not itself load-bearing for correctness (see class javadoc). */
  private static final int FILTER_ORDER = 100;

  @Bean
  public FilterRegistrationBean<OperatorSessionFilter> operatorSessionFilterRegistration(
      OperatorTokenSigningKey signingKey, Clock clock) {
    FilterRegistrationBean<OperatorSessionFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new OperatorSessionFilter(signingKey.secretBytes(), clock));
    registration.addUrlPatterns(URL_PATTERNS);
    registration.setOrder(FILTER_ORDER);
    return registration;
  }

  /**
   * The clock {@link OperatorSessionFilter} checks a token's {@code exp} against. The system UTC
   * clock (all Native timestamps are UTC) — injected, not called directly, so a test can pin "now"
   * deterministically.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
