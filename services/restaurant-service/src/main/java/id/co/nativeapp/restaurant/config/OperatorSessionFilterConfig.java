package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.security.OperatorSessionFilter;
import id.co.nativeapp.security.OperatorTokenSigningKey;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared {@code libs/security} {@link OperatorSessionFilter} over
 * restaurant-service's POS write routes (ADR 0049 P2) — the vertical opt-in the filter's own
 * javadoc calls out (it is NOT auto-registered fleet-wide).
 *
 * <p><strong>Scope.</strong> Path-scoped to the routes that record a sale or its component parts:
 * {@code /api/v1/sales/**}, {@code /api/v1/orders/**}, {@code /api/v1/payments/**}, {@code
 * /api/v1/bills/**}, {@code /api/v1/tables/**} — every controller {@link
 * id.co.nativeapp.restaurant.sale.service.SaleWriter#create}/{@link
 * id.co.nativeapp.restaurant.sale.service.SaleWriter#recordInCurrentTx} is reachable from
 * (SaleController direct, OrderWriter checkout/park/payParked, BillWriter open/payBill,
 * PaymentCaptureWriter capture, TableController's till surface). Unlike {@code
 * SelfOrderTokenFilter} (the anonymous surface's SOLE gate), this filter is harmless to register
 * more broadly than strictly necessary — {@link OperatorSessionFilter} no-ops on any request that
 * carries no {@code X-Operator-Session} header — but the explicit route list keeps its footprint
 * documented and minimal rather than restaurant-wide.
 *
 * <p><strong>Order.</strong> This filter does not touch {@code TenantContext} (by design — see
 * {@link OperatorSessionFilter} javadoc), so unlike {@code SelfOrderFilterConfig}'s {@code
 * HIGHEST_PRECEDENCE + 1} (which deliberately runs BEFORE tenant binding, because the self-order
 * surface establishes its OWN tentative tenant scope), this filter has no correctness dependency on
 * running before or after tenant binding. It is registered at a modest positive order — comfortably
 * after {@code DevTenantFilter} ({@code HIGHEST_PRECEDENCE + 10}, dev profile) and after the
 * non-dev Spring Security {@code FilterChainProxy} (registered at Spring Boot's default {@code
 * -100}, which runs {@code libs.security.TenantBindingFilter} internally) — so it conceptually sits
 * "after tenant binding" in both profiles without any of {@code SelfOrderFilterConfig}'s
 * underflow-prone {@code HIGHEST_PRECEDENCE} arithmetic (the same care, applied the other way).
 */
@Configuration
public class OperatorSessionFilterConfig {

  // Both the BARE path and the /* prefix for each route: the primary record-sale endpoint is
  // exactly POST /api/v1/sales (no sub-path), and while a servlet /foo/* prefix mapping also
  // matches
  // /foo in Tomcat, registering the bare path explicitly makes the filter↔endpoint coupling robust
  // to a rename or a stricter container (code review W1) — a silently-unmatched filter here would
  // drop the operator on the main till endpoint and fall back to the device actor with no signal.
  private static final String[] URL_PATTERNS = {
    "/api/v1/sales", "/api/v1/sales/*",
    "/api/v1/orders", "/api/v1/orders/*",
    "/api/v1/payments", "/api/v1/payments/*",
    "/api/v1/bills", "/api/v1/bills/*",
    "/api/v1/tables", "/api/v1/tables/*"
  };

  /**
   * A sane, documented order: after {@code DevTenantFilter} (dev) and after the Spring Security
   * chain (non-dev) — see class javadoc. Not itself load-bearing for correctness (the filter never
   * reads {@code TenantContext}), only for keeping the request pipeline's intent legible.
   */
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
   * deterministically (mirrors employee-service's {@code TimeConfig}).
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
