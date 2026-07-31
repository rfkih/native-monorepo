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

  /**
   * Roles allowed on the employee self-service surface ({@code /api/v1/me/**}) — every business
   * role: the endpoints resolve the caller from the token's own sub, so there is no cross-user
   * exposure to widen.
   */
  private static final String[] ME_ROLES = {"owner", "manager", "cashier", "employee"};

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

  /**
   * {@code GET /api/v1/users/me/pages} — the caller's own page-access mode. Allowed for EVERY
   * business role (a login reads its own grants) via {@code ME_ROLES}. Like {@link
   * #userMeOutletsRoute}, this exact path must be ordered before the general {@code /users/**}
   * (DASHBOARD_ROLES) route — {@code @Order(HIGHEST_PRECEDENCE)} makes the specific match win, so
   * an {@code employee}/{@code cashier} login is not 403'd reading its own page grants.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> userMePagesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-user-me-pages")
        .route(path("/api/v1/users/me/pages"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(ME_ROLES))
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

  /**
   * {@code GET /api/v1/companies/mine} — the caller's OWN company memberships (the switcher/session
   * bootstrap, ADR 0021). Allowed for EVERY business role via {@code ME_ROLES}: the console session
   * bootstraps the active company from this endpoint for all personas — a {@code cashier} needs it
   * to open the POS (the exact regression the dashboard-only gate caused), and the response is
   * derived strictly from the caller's own verified {@code company_id} claim, so there is no
   * cross-user or cross-tenant exposure to widen. Tenant-OPTIONAL like {@link #companiesRoute}: a
   * 0-company login gets {@code []} instead of a 403.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: the general {@code /companies/**} route
   * below would otherwise swallow this exact path and 403 every non-dashboard role — the same
   * first-match-wins pattern as {@link #currentCompanyRoute}.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> myCompaniesRoute(
      GatewayRouteProperties routes, RedisTokenBucketRateLimiter limiter) {
    return GatewayRouterFunctions.route("org-service-my-companies")
        .route(path("/api/v1/companies/mine"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(ME_ROLES))
        .filter(TenantContextHeaderFilter.tenantOptional())
        .build();
  }

  /**
   * Company creation/management — owner/manager. Uses the TENANT-OPTIONAL tenant filter (ADR 0021):
   * a valid 0-company token (a fresh login pre-onboarding, or one adding its first business) must
   * reach {@code POST /api/v1/companies} + {@code GET /api/v1/companies/mine}; it is forwarded
   * tenant-less and org-service's own per-path tenant-optional matcher enforces exactly those two —
   * every other companies path still 403s at the service edge. Tokens WITH companies behave as on
   * every other route (validated active-company selection).
   */
  @Bean
  RouterFunction<ServerResponse> companiesRoute(
      GatewayRouteProperties routes, RedisTokenBucketRateLimiter limiter) {
    return GatewayRouterFunctions.route("org-service")
        .route(path("/api/v1/companies/**"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(TenantContextHeaderFilter.tenantOptional())
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

  /**
   * Pricing-rule preview under {@code /api/v1/pricing/**} (restaurant-service, Phase 5 offline
   * mode, ADR 0028) — {@code GET /api/v1/pricing/effective-rules}: a POS client caches the
   * effective tax/service-charge rules to compute PROVISIONAL pricing while offline. Restaurant's
   * surface is grandfathered unprefixed (like {@link #salesRoute}/{@link #menuRoute}), so this
   * rides a fresh, restaurant-unprefixed path — POS_ROLES, same as every other restaurant POS
   * route.
   */
  @Bean
  RouterFunction<ServerResponse> pricingRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-pricing")
        .route(path("/api/v1/pricing/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * All carwash POS traffic under {@code /api/v1/carwash/**} (carwash-service) — POS surface.
   * Vertical services after restaurant are namespaced by vertical (the {@code /api/v1/ap/**}
   * precedent applied at vertical scope): restaurant's unprefixed paths ({@code /orders}, {@code
   * /menu}, …) are grandfathered, but carwash and every later vertical prefix so they can never
   * collide with each other. Covers catalog (packages/addons/staff-profiles) and tickets
   * (quote/checkout/read).
   */
  @Bean
  RouterFunction<ServerResponse> carwashRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("carwash-service")
        .route(path("/api/v1/carwash/**"), http())
        .before(uri(routes.carwashService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Promotion rules + coupons administration under {@code /api/v1/promotions/**}
   * (restaurant-service, ADR 0026) — an owner/manager back-office surface: rules and coupon codes
   * change what customers pay, so the cashier POS role has no business here. The cashier-facing
   * half of promotions (a coupon applied at quote/checkout) rides the already-routed POS order
   * paths; the vertical services' promo admin rides their {@code /api/v1/<vertical>/**} routes with
   * the same owner/manager guard enforced service-side.
   */
  @Bean
  RouterFunction<ServerResponse> promotionsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-promotions")
        .route(path("/api/v1/promotions/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Loyalty earn-rule administration ({@code /api/v1/loyalty/earn-rules/**}) — owner/manager only:
   * earn rules decide how much every sale is worth in points (money-adjacent config). Ordered
   * before the general POS-roles loyalty route below ({@code @Order(HIGHEST_PRECEDENCE)} — first
   * match wins, the {@code /users/me/**} precedent).
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> loyaltyEarnRulesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("loyalty-service-earn-rules")
        .route(path("/api/v1/loyalty/earn-rules/**"), http())
        .before(uri(routes.loyaltyService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Restaurant gift-card sales ({@code /api/v1/gift-card-sales}) — a POS till operation (ADR 0027):
   * selling a card mints it and emits {@code GiftCardSold}. Unprefixed because restaurant's surface
   * is grandfathered unprefixed; deliberately NOT under {@code /api/v1/loyalty/**}, which routes to
   * loyalty-service (the card is SOLD at the vertical till, the authoritative card aggregate
   * materialises in loyalty-service via the event). The carwash/barbershop equivalents ride their
   * existing vertical-prefixed routes.
   */
  @Bean
  RouterFunction<ServerResponse> giftCardSalesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-gift-card-sales")
        .route(path("/api/v1/gift-card-sales/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Loyalty POS surface ({@code /api/v1/loyalty/**} — member lookup/enroll, gift-card lookup) —
   * POS_ROLES: the cashier attaches members and gift cards at the till (ADR 0027). This is a CLIENT
   * call through the gateway, not a service-to-service call; the verticals themselves never call
   * loyalty-service synchronously (rule 2) — they validate against locally cached read models. PII
   * stays inside loyalty-service; responses carry only the display name + a masked phone tail.
   */
  @Bean
  RouterFunction<ServerResponse> loyaltyRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("loyalty-service")
        .route(path("/api/v1/loyalty/**"), http())
        .before(uri(routes.loyaltyService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * All barbershop POS traffic under {@code /api/v1/barbershop/**} (barbershop-service) — POS
   * surface, vertical-prefixed exactly like {@link #carwashRoute} (ADR 0023/0024).
   */
  @Bean
  RouterFunction<ServerResponse> barbershopRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("barbershop-service")
        .route(path("/api/v1/barbershop/**"), http())
        .before(uri(routes.barbershopService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Module entitlements under {@code /api/v1/entitlements/**} (entitlement-service) — the
   * owner/manager self-serve path for activating a module on an EXISTING company (ADR 0024: new
   * modules are default-granted only at company creation; a Flyway backfill into the FORCE-RLS
   * {@code tenant_entitlement} is impossible by design, so existing companies grant through this
   * surface and the grant flows out via the outbox as {@code EntitlementGranted}).
   */
  @Bean
  RouterFunction<ServerResponse> entitlementsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("entitlement-service")
        .route(path("/api/v1/entitlements/**"), http())
        .before(uri(routes.entitlementService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  // ---------------------------------------------------------------------------
  // employee-service (self-service /me — every business role)
  // ---------------------------------------------------------------------------

  /**
   * The employee self-service surface: own profile / payslips / sales. Allowed for EVERY business
   * role including {@code employee} — the downstream resolves the caller strictly from the injected
   * {@code X-Actor} (the JWT sub), so the surface cannot read anyone else's data. A fresh path
   * prefix (no overlap with the dashboard routes), so no {@code @Order} games are needed.
   */
  @Bean
  RouterFunction<ServerResponse> meRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-me")
        .route(path("/api/v1/me/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(ME_ROLES))
        .filter(tenantFilter)
        .build();
  }

  // ---------------------------------------------------------------------------
  // employee-service (owner dashboard — HR + payroll)
  // ---------------------------------------------------------------------------

  /**
   * HR records + assignments + compensation ({@code /api/v1/employees/**}) — strictly the
   * owner/manager dashboard surface: employee rows carry PII and salary state, so the POS roles
   * never reach them. Also covers the local org-read-model lookup ({@code
   * /api/v1/employees/org-units}).
   */
  @Bean
  RouterFunction<ServerResponse> employeesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-employees")
        .route(path("/api/v1/employees/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /** Payroll runs (execute + read summaries/payslips/allocations) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> payrollRunsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-runs")
        .route(path("/api/v1/payroll-runs/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /** Payroll setup (catalog/statutory status + illustrative seed) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> payrollSetupRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-setup")
        .route(path("/api/v1/payroll-setup/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
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

  /** AR customers (finance-service) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> customersRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-customers")
        .route(path("/api/v1/customers/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /** AR invoices (finance-service) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> invoicesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-invoices")
        .route(path("/api/v1/invoices/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /** AR aging + other AR reads (finance-service) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> arRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-ar")
        .route(path("/api/v1/ar/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /** AP vendors (finance-service) — owner/manager only. */
  @Bean
  RouterFunction<ServerResponse> vendorsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-vendors")
        .route(path("/api/v1/vendors/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * All AP reads/writes under {@code /api/v1/ap/**} (finance-service) — owner/manager only. AP
   * bills are namespaced {@code /api/v1/ap/bills} (NOT {@code /api/v1/bills}) to avoid colliding
   * with restaurant-service's already-shipped guest-tab "open bills" route ({@link #billsRoute},
   * POS surface); the AP aging report is {@code /api/v1/ap/aging}. Vendors are the separate {@link
   * #vendorsRoute} ({@code /api/v1/vendors/**} — no collision). One {@code /api/v1/ap/**} route
   * therefore covers both AP bills and AP aging.
   */
  @Bean
  RouterFunction<ServerResponse> apRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-ap")
        .route(path("/api/v1/ap/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Bank accounts + their statement lines (finance-service) — owner/manager only. {@code
   * /api/v1/bank-accounts/**} covers CRUD plus the nested {@code
   * /api/v1/bank-accounts/{id}/statement-lines} import/list (Phase 3 bank reconciliation, ADR
   * 0016). No collision with any existing route.
   */
  @Bean
  RouterFunction<ServerResponse> bankAccountsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-bank-accounts")
        .route(path("/api/v1/bank-accounts/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Bank reconciliation (finance-service) — owner/manager only. {@code /api/v1/bank/**} covers
   * {@code POST /api/v1/bank/reconcile/{lineId}} and {@code GET /api/v1/bank/reconciliation} (Phase
   * 3 bank reconciliation, ADR 0016). No collision with any existing route.
   */
  @Bean
  RouterFunction<ServerResponse> bankRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-bank")
        .route(path("/api/v1/bank/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Tax / PPN (finance-service) — owner/manager only. {@code /api/v1/tax/**} covers the VAT report
   * ({@code GET /api/v1/tax/vat/return}), filing + history + detail ({@code
   * /api/v1/tax/vat/returns}), settlement ({@code POST /api/v1/tax/vat/returns/{id}/settle}), and
   * the e-Faktur CSV export ({@code GET /api/v1/tax/vat/efaktur}) (Phase 4 Tax / PPN, ADR 0017).
   * Fresh prefix — no collision.
   */
  @Bean
  RouterFunction<ServerResponse> taxRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-tax")
        .route(path("/api/v1/tax/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Budgets (finance-service) — owner/manager only. {@code /api/v1/budgets/**} covers budget CRUD,
   * the budget-vs-actual variance ({@code GET /api/v1/budgets/{id}/actuals}), and the
   * chart-of-account picker ({@code GET /api/v1/budgets/accounts}) (Phase 5 Cash-flow &amp;
   * Budgets, ADR 0019). Fresh prefix — no collision.
   */
  @Bean
  RouterFunction<ServerResponse> budgetsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-budgets")
        .route(path("/api/v1/budgets/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Fixed assets (finance-service) — owner/manager only. {@code /api/v1/assets/**} covers the asset
   * register, acquire, and the monthly amortization run ({@code POST /api/v1/assets/runs}) (Phase 6
   * fixed assets &amp; deferrals, ADR 0020). Fresh prefix — no collision.
   */
  @Bean
  RouterFunction<ServerResponse> assetsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-assets")
        .route(path("/api/v1/assets/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Deferrals (finance-service) — owner/manager only. {@code /api/v1/deferrals/**} covers prepaid
   * expenses + deferred revenue (Phase 6, ADR 0020). Fresh prefix — no collision.
   */
  @Bean
  RouterFunction<ServerResponse> deferralsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-deferrals")
        .route(path("/api/v1/deferrals/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(DASHBOARD_ROLES))
        .filter(tenantFilter)
        .build();
  }
}
