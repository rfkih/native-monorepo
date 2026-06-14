package id.co.nativeapp.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import id.co.nativeapp.gateway.filter.RateLimitFilter;
import id.co.nativeapp.gateway.filter.RedisTokenBucketRateLimiter;
import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Edge routing (Spring Cloud Gateway, servlet/webmvc stack).
 *
 * <p>Three routes, targets from {@link GatewayRouteProperties} (defaulted for the docker dev
 * stack):
 *
 * <ul>
 *   <li>{@code /api/v1/companies/**} → org-service,
 *   <li>{@code /api/v1/sales/**} → restaurant-service,
 *   <li>{@code /api/v1/revenue/**} → finance-service.
 * </ul>
 *
 * <p>Every route carries the same two filters, in order: the {@link RateLimitFilter} (per-tenant
 * token bucket → {@code 429} when exceeded) and the {@link TenantContextHeaderFilter} (strip
 * client-supplied tenant headers, inject the JWT-derived {@code X-Company-Id} / {@code X-Actor} /
 * {@code X-Roles}). Both run only after the security chain has validated the bearer token, so an
 * unauthenticated request is already a {@code 401} and never reaches a route.
 *
 * <p>{@code HandlerFunctions.http()} is the reverse-proxy handler; {@code
 * BeforeFilterFunctions.uri} sets the downstream base URI it forwards to. The full inbound path
 * (e.g. {@code /api/v1/sales/123}) is preserved to the downstream, matching the {@code /api/v1/...}
 * paths the services already expose.
 */
@Configuration
public class RoutingConfig {

  @Bean
  RouterFunction<ServerResponse> companiesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service")
        .route(path("/api/v1/companies/**"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> salesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service")
        .route(path("/api/v1/sales/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> revenueRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service")
        .route(path("/api/v1/revenue/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(tenantFilter)
        .build();
  }
}
