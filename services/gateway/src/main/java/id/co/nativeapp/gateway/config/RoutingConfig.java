package id.co.nativeapp.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import id.co.nativeapp.gateway.filter.RoleAuthorizationFilter;
import id.co.nativeapp.gateway.filter.TenantContextHeaderFilter;
import id.co.nativeapp.gateway.ratelimit.AnonymousRateLimitFilter;
import id.co.nativeapp.gateway.ratelimit.RateLimitFilter;
import id.co.nativeapp.gateway.ratelimit.RedisTokenBucketRateLimiter;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
  // org-service (public — unauthenticated)
  // ---------------------------------------------------------------------------

  /**
   * Public sign-up route — forwarded to org-service with NO authentication, NO role check, and NO
   * tenant-context header injection: the request carries no JWT, so there is no tenant to inject
   * and {@link TenantContextHeaderFilter} is deliberately absent. The org-service permits this
   * exact path ({@code native.security.public-paths}) and owns the logic once it arrives
   * token-free.
   *
   * <p>The route IS throttled: the tenant {@link RateLimitFilter} keys on the JWT {@code
   * (company_id, sub)} and cannot protect an anonymous endpoint, so this route carries the
   * dedicated {@link AnonymousRateLimitFilter} instead — a per-client-IP token bucket with its own
   * (much tighter) knobs under {@code native.gateway.rate-limit.signup}. A CAPTCHA/proof-of-work
   * challenge remains a follow-up on top of the IP throttle.
   */
  @Bean
  RouterFunction<ServerResponse> signupRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      RateLimitProperties rateLimits) {
    return GatewayRouterFunctions.route("org-service-signup")
        .route(path("/api/v1/signup"), http())
        .before(uri(routes.orgService()))
        .filter(new AnonymousRateLimitFilter(limiter, rateLimits.signup()))
        .build();
  }

  // ---------------------------------------------------------------------------
  // org-service (owner dashboard)
  // ---------------------------------------------------------------------------

  /**
   * {@code GET /api/v1/users/me/outlets} — the caller's own outlet assignments for the POS outlet
   * picker intersection. Allowed for every business role INCLUDING {@code cashier}: cashiers are
   * the primary POS users and need to know which outlets they are assigned to. This is a
   * tenant-scoped read of the caller's own data — no cross-tenant exposure.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: RouterFunction beans are matched in
   * order (first match wins, NOT most-specific wins), and the general {@code /users/**} route below
   * would otherwise swallow this exact path and {@code 403} the cashier. Ordering this route first
   * makes the specific match take precedence; every other {@code /users/**} path falls through to
   * the dashboard-gated route — exactly the same pattern as {@link #currentCompanyRoute}.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> userMeOutletsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-user-me-outlets")
        .route(path("/api/v1/users/me/outlets"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> usersRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-users")
        .route(path("/api/v1/users/**"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/companies/current} — the caller reading their OWN bound company. Allowed for
   * every business role INCLUDING {@code cashier}: the cashier POS needs the company's {@code
   * firstBusinessId} (to ring up sales) plus its name/base currency to render. This is a
   * tenant-scoped read of the caller's own company — no cross-tenant exposure — so it is safe on
   * the POS surface, unlike the rest of {@code /api/v1/companies/**} (company creation /
   * management), which stays owner/manager-only via {@link #companiesRoute}.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: RouterFunction beans are matched in
   * order (first match wins, NOT most-specific wins), and the general {@code /companies/**} route
   * below would otherwise swallow this exact path and 403 the cashier. Ordering this route first
   * makes the specific match take precedence; every other {@code /companies/**} path falls through
   * to the dashboard-gated route.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> currentCompanyRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-current-company")
        .route(path("/api/v1/companies/current"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

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

  @Bean
  RouterFunction<ServerResponse> orgUnitsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-org-units")
        .route(path("/api/v1/org-units/**"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * POS outlet picker — {@code GET /api/v1/outlets} returns active outlets for the bound company.
   *
   * <p>Allowed for every business role including {@code cashier}: cashiers are the primary POS
   * users and need the outlet list to open a sale. This is a tenant-scoped read of the caller's own
   * company's outlets (no cross-tenant exposure), distinct from the full org-tree management
   * endpoint ({@code /api/v1/org-units/**}) which remains owner/manager-only.
   */
  @Bean
  RouterFunction<ServerResponse> outletsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-outlets")
        .route(path("/api/v1/outlets"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> consolidationGroupsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-consolidation-groups")
        .route(path("/api/v1/consolidation-groups/**"), http())
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

  @Bean
  RouterFunction<ServerResponse> billsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-bills")
        .route(path("/api/v1/bills/**"), http())
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

  @Bean
  RouterFunction<ServerResponse> groupsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-groups")
        .route(path("/api/v1/groups/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> closesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-closes")
        .route(path("/api/v1/closes/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }
}
