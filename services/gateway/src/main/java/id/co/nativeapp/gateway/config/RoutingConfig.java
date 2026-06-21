package id.co.nativeapp.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import id.co.nativeapp.gateway.filter.RoleAuthorizationFilter;
import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import id.co.nativeapp.gateway.ratelimit.RateLimitFilter;
import id.co.nativeapp.gateway.ratelimit.RedisTokenBucketRateLimiter;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Edge routing (Spring Cloud Gateway, servlet/webmvc stack).
 *
 * <p>The console is two role-separated surfaces behind one gateway:
 *
 * <ul>
 *   <li><b>Cashier POS</b> ({@code owner}/{@code manager}/{@code cashier}): {@code
 *       /api/v1/menu/**}, {@code /api/v1/orders/**}, {@code /api/v1/sales/**}, {@code
 *       /api/v1/payments/**}, {@code /api/v1/tables/**} → restaurant-service.
 *   <li><b>Owner dashboard</b> ({@code owner}/{@code manager} only): {@code /api/v1/companies/**} →
 *       org-service; {@code /api/v1/revenue/**}, {@code /api/v1/pnl/**}, {@code
 *       /api/v1/statements/**} → finance-service.
 * </ul>
 *
 * <p>Every route carries the same filter chain, in order: {@link RateLimitFilter} (per-tenant token
 * bucket → {@code 429}); {@link RoleAuthorizationFilter} (the route's allowed roles → {@code 403}
 * otherwise — this is the API half of the surface separation); {@link TenantContextHeaderFilter}
 * (strip client tenant headers, inject the JWT-derived {@code X-Company-Id}/{@code X-Actor}/{@code
 * X-Roles}). All run only after the security chain validated the bearer token, so an
 * unauthenticated request is already a {@code 401} and never reaches a route.
 *
 * <p>Targets come from {@link GatewayRouteProperties} (defaulted for the docker dev stack). The
 * full inbound path is preserved to the downstream, matching the {@code /api/v1/...} paths the
 * services expose.
 */
@Configuration
public class RoutingConfig {

  /** Roles allowed on the cashier POS surface. */
  private static final String[] POS_ROLES = {"owner", "manager", "cashier"};

  /** Roles allowed on the owner/manager dashboard surface. */
  private static final String[] DASHBOARD_ROLES = {"owner", "manager"};

  // ---------------------------------------------------------------------------
  // org-service (owner dashboard)
  // ---------------------------------------------------------------------------
  @Bean
  RouterFunction<ServerResponse> companiesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service")
        .route(path("/api/v1/companies/**"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  // ---------------------------------------------------------------------------
  // restaurant-service (cashier POS)
  // ---------------------------------------------------------------------------
  @Bean
  RouterFunction<ServerResponse> salesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service")
        .route(path("/api/v1/sales/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> menuRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-menu")
        .route(path("/api/v1/menu/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> ordersRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-orders")
        .route(path("/api/v1/orders/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> paymentsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-payments")
        .route(path("/api/v1/payments/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> tablesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-tables")
        .route(path("/api/v1/tables/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  // ---------------------------------------------------------------------------
  // finance-service (owner dashboard)
  // ---------------------------------------------------------------------------
  @Bean
  RouterFunction<ServerResponse> revenueRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service")
        .route(path("/api/v1/revenue/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> pnlRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-pnl")
        .route(path("/api/v1/pnl/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> statementsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-statements")
        .route(path("/api/v1/statements/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }
}
