package id.co.nativeapp.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import id.co.nativeapp.gateway.filter.AnonymousTenantHeaderStripFilter;
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
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Edge routing (Spring Cloud Gateway, servlet/webmvc stack).
 *
 * <p>The console is a set of PRESET-ROLE capability surfaces behind one gateway (preset role-based
 * access model Phase 1 — 8 roles: {@code owner}/{@code manager}/{@code cashier}/{@code employee}/
 * {@code hr}/{@code accountant}/{@code chef}/{@code waitress}; a login may hold several, access is
 * the union):
 *
 * <ul>
 *   <li><b>POS</b> ({@link #POS_ROLES} — owner/manager/cashier/chef/waitress): the till + floor —
 *       {@code /api/v1/menu/**}, {@code /api/v1/orders/**}, {@code /api/v1/sales/**}, {@code
 *       /api/v1/payments/**}, {@code /api/v1/tables/**}, the vertical POS surfaces, and more →
 *       restaurant/carwash/barbershop/loyalty/payment-service.
 *   <li><b>OPS</b> ({@link #OPS_ROLES} — owner/manager): org structure/team, promotions,
 *       entitlements, company management → org-service/restaurant-service/entitlement-service.
 *   <li><b>REPORTS</b> ({@link #REPORTS_ROLES} — owner/manager/accountant): {@code
 *       /api/v1/revenue/**}, {@code /api/v1/pnl/**}, {@code /api/v1/statements/**} →
 *       finance-service.
 *   <li><b>FINANCE</b> ({@link #FINANCE_ROLES} — owner/accountant): the detailed back-office books
 *       (AP/AR/bank/tax/budgets/assets/deferrals/…) → finance-service.
 *   <li><b>HR</b> ({@link #HR_ROLES} — owner/manager/hr): employee records, expense claims, leave,
 *       overtime, work calendar → employee-service.
 *   <li><b>PAYROLL</b> ({@link #PAYROLL_ROLES} — owner/hr): payroll runs/setup/reports →
 *       employee-service.
 *   <li><b>OWNER-ONLY</b> ({@link #OWNER_ROLES}): the highest-sensitivity PII exports and QRIS
 *       payment-settings administration.
 *   <li><b>ME</b> ({@link #ME_ROLES} — every business role): {@code /api/v1/me/**} self-service.
 * </ul>
 *
 * <p>Every AUTHENTICATED route carries the same filter chain, in order: {@link RateLimitFilter}
 * (per-tenant token bucket → {@code 429}); {@link RoleAuthorizationFilter} (the route's allowed
 * roles → {@code 403} otherwise — this is the API half of the surface separation); {@link
 * TenantContextHeaderFilter} (strip client tenant headers, inject the JWT-derived {@code
 * X-Company-Id}/{@code X-Actor}/{@code X-Roles}). All run only after the security chain validated
 * the bearer token, so an unauthenticated request is already a {@code 401} and never reaches a
 * route.
 *
 * <p>Two routes are deliberately ANONYMOUS (no JWT, so none of the above): the public sign-up route
 * ({@link #signupRoute}) and the public self-order QR route ({@link #selfOrderRoute}, Phase 6, ADR
 * 0029). Each substitutes {@link AnonymousRateLimitFilter} (a per-client-IP bucket, its own Redis
 * namespace) for {@link RateLimitFilter}, carries no {@link RoleAuthorizationFilter}, and cannot
 * use {@link TenantContextHeaderFilter} (it requires a validated JWT and would 401 an anonymous
 * caller) — the self-order route instead carries the strip-only {@link
 * AnonymousTenantHeaderStripFilter} so a caller can never inject a trusted tenant header.
 *
 * <p>Targets come from {@link GatewayRouteProperties} (defaulted for the docker dev stack). The
 * full inbound path is preserved to the downstream, matching the {@code /api/v1/...} paths the
 * services expose.
 */
@Configuration
public class RoutingConfig {

  /**
   * Roles allowed on the operational POS/till surface (preset role-based access model Phase 1):
   * {@code owner}/{@code manager} (full access) plus {@code cashier}/{@code chef}/{@code waitress}
   * (operational-only — the floor rings the till exactly like a cashier, no back-office reach).
   */
  private static final String[] POS_ROLES = {"owner", "manager", "cashier", "chef", "waitress"};

  /**
   * Roles allowed to REVERSE a captured sale — the refund/void money-out actions ({@code POST
   * /api/v1/payments/*}{@code /refund}, {@code /void}). {@code owner}/{@code manager} only: a
   * refund returns money to a customer and posts a GL reversal (ADR 0006, ADR 0061), so it is a
   * MANAGER decision — narrower than the general {@link #POS_ROLES} till surface (which a {@code
   * cashier}/{@code chef}/{@code waitress} rings). Capture and the receipt read stay POS_ROLES;
   * only these two reversal sub-paths escalate, via a HIGHEST_PRECEDENCE route ordered before
   * {@link #paymentsRoute}. On a shared device terminal a cashier must have an owner/manager
   * ELEVATION (ADR 0049 P3b) on their bearer to pass — the console gates the affordance
   * identically.
   */
  private static final String[] SALE_REVERSAL_ROLES = {"owner", "manager"};

  /**
   * Roles allowed on the OPERATIONS surface — org structure/team, promotions, entitlements,
   * consolidation groups, self-order-access administration. {@code owner}/{@code manager} only: a
   * manager runs operations but does not see the detailed books, HR, or payroll (preset role-based
   * access model Phase 1 — narrower than the old, single {@code DASHBOARD_ROLES} bucket this
   * replaces).
   */
  private static final String[] OPS_ROLES = {"owner", "manager"};

  /**
   * Roles allowed to READ the org-unit tree — the EXACT {@code GET /api/v1/org-units} flat list
   * only ({@code useOrgUnits}: the console builds the BU→outlet tree client-side from {@code
   * parentId}). Every OFFICE role ({@code owner}/{@code manager}/{@code hr}/{@code accountant}):
   * the People/HR area needs unit names to scope employees + payroll and per-unit reports name
   * their units, but only {@code owner}/{@code manager} may create/rename/move/deactivate units.
   *
   * <p><strong>Exact path, deliberately NOT {@code /**}.</strong> The {@code /api/v1/org-units}
   * prefix is NOT structure-only — two sensitive sibling GETs live under it: {@code GET
   * /{outletId}/device-credential} (reveals a DECRYPTED per-outlet till password) and {@code GET
   * /{orgUnitId}/users} (per-unit staffing). A {@code /**} carve-out would hand those to {@code
   * hr}/{@code accountant} — a decrypted-credential disclosure + POS escalation across the very
   * floor/office boundary this model enforces. So the read route matches ONLY the exact list path;
   * every {@code /{id}/...} sub-resource (and all write verbs) falls through to the general {@link
   * #OPS_ROLES} route (owner/manager). Floor roles are excluded entirely — they use the narrower
   * {@code GET /api/v1/outlets} picker (preset role-based access model Phase 2).
   */
  private static final String[] ORG_READ_ROLES = {"owner", "manager", "hr", "accountant"};

  /**
   * Roles allowed on the financial REPORTS surface (P&amp;L, statements, revenue) — {@code owner}/
   * {@code manager}/{@code accountant}: a manager still reads the top-line reports, an accountant
   * reads them as part of the books.
   */
  private static final String[] REPORTS_ROLES = {"owner", "manager", "accountant"};

  /**
   * Roles allowed on the detailed FINANCE back-office surface (AP/AR/bank/tax/budgets/assets/
   * deferrals/opening-balances/platform-settlements/payroll-liabilities/consolidation groups/
   * closes) — {@code owner}/{@code accountant} only. {@code manager} is deliberately EXCLUDED: a
   * manager runs operations, not the detailed books (preset role-based access model Phase 1).
   */
  private static final String[] FINANCE_ROLES = {"owner", "accountant"};

  /**
   * Roles allowed on the HR surface (employee records, expense claims, leave, overtime, work
   * calendar, leave balances) — {@code owner}/{@code manager}/{@code hr}.
   */
  private static final String[] HR_ROLES = {"owner", "manager", "hr"};

  /**
   * Roles allowed on the PAYROLL surface (payroll runs/setup/reports) — {@code owner}/{@code hr}
   * only. {@code manager} is deliberately EXCLUDED: payroll is not an operations concern (preset
   * role-based access model Phase 1). The highest-sensitivity payroll PII (bank file, authorized
   * payslips, 1721a1, bpjs-summary) stays narrower still at {@link #OWNER_ROLES} (unchanged).
   */
  private static final String[] PAYROLL_ROLES = {"owner", "hr"};

  /**
   * Roles allowed on the employee self-service surface ({@code /api/v1/me/**}) — every business
   * role: the endpoints resolve the caller from the token's own sub, so there is no cross-user
   * exposure to widen.
   */
  private static final String[] ME_ROLES = {
    "owner", "manager", "cashier", "employee", "hr", "accountant", "chef", "waitress"
  };

  /**
   * Roles allowed on the OWNER-ONLY surface — narrower than every other capability array above.
   * Reserved for the highest-sensitivity PII exports: the payroll net-pay bank file with decrypted
   * bank accounts (Track P phase P5); the {@code 1721-A1} statutory export (NIK/NPWP) and the
   * printable-payslip authorized read (decrypted salary) (Track P phase P9); {@code bpjs-summary}
   * (per-employee wage — a P9 judgment call, see {@code payrollReportBpjsSummaryRoute}'s javadoc);
   * and QRIS payment-settings administration (the merchant's own PSP server key, ADR 0045).
   */
  private static final String[] OWNER_ROLES = {"owner"};

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
        .filter(new AnonymousRateLimitFilter(limiter, rateLimits.signup(), "anon:signup:"))
        // Same strip as self-order (Phase 6 hardening): signup previously forwarded any
        // client-supplied X-Company-Id/X-Actor/X-Roles untouched — harmless only as long as
        // org-service's signup path ignores them, which is one refactor away from not being true.
        .filter(new AnonymousTenantHeaderStripFilter())
        .build();
  }

  // ---------------------------------------------------------------------------
  // restaurant-service (self-order QR — the program's only ANONYMOUS business route)
  // ---------------------------------------------------------------------------

  /**
   * Public self-order QR route — {@code /api/v1/self-order/**} forwarded to restaurant-service with
   * NO authentication, NO role check, and NO {@link TenantContextHeaderFilter} (Phase 6, ADR 0029).
   * A diner scans a table's QR code and never logs in, so there is no JWT to validate, no tenant
   * claim to trust, and no business role to gate on — restaurant-service owns the actual session
   * logic once the request arrives, keyed by the QR-issued {@code X-Self-Order-Token} it validates
   * itself (that header rides through this gateway untouched by every filter below).
   *
   * <p><strong>Throttle.</strong> The tenant {@link RateLimitFilter} keys on the JWT {@code
   * (company_id, sub)} and cannot protect an anonymous endpoint, so this route carries the
   * dedicated {@link AnonymousRateLimitFilter} instead — a per-client-IP token bucket under {@code
   * native.gateway.rate-limit.self-order}, in its OWN Redis namespace ({@code anon:self-order:} —
   * never {@code anon:signup:}) so a busy dining room can neither starve nor be starved by the
   * tenant-creation throttle. Fail-closed like every other bucket: a Redis outage denies, never
   * unmeters.
   *
   * <p><strong>Spoof defence.</strong> {@link TenantContextHeaderFilter} cannot run here (it 401s
   * an unauthenticated caller), so it cannot do its usual header strip either. {@link
   * AnonymousTenantHeaderStripFilter} does the strip half only, unconditionally removing any
   * client-supplied {@code X-Company-Id}/{@code X-Actor}/{@code X-Roles} before the request reaches
   * restaurant-service — an anonymous diner can never inject a trusted tenant/actor/role header.
   */
  @Bean
  RouterFunction<ServerResponse> selfOrderRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      RateLimitProperties rateLimits) {
    return GatewayRouterFunctions.route("restaurant-service-self-order")
        .route(path("/api/v1/self-order/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new AnonymousRateLimitFilter(limiter, rateLimits.selfOrder(), "anon:self-order:"))
        .filter(new AnonymousTenantHeaderStripFilter())
        .build();
  }

  /**
   * Self-order QR ADMINISTRATION ({@code /api/v1/self-order-access/**}) — the owner/manager surface
   * that mints and ROTATES a table's tokens (Phase 6, ADR 0029). This is an ordinary AUTHENTICATED
   * dashboard route (JWT + owner/manager role + trusted tenant headers), NOT part of the anonymous
   * diner surface — and it is a SEPARATE path prefix: Spring's {@code /api/v1/self-order/**}
   * pattern does NOT match the sibling segment {@code self-order-access}, so without this bean the
   * mint/ rotate endpoints would 404 at the gateway and rotation — the token's ONLY revocation
   * control — would be unreachable in production (a leaked no-expiry QR could never be revoked).
   * Mirrors {@link #promotionsRoute}'s money-adjacent-admin shape.
   */
  @Bean
  RouterFunction<ServerResponse> selfOrderAccessRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-self-order-access")
        .route(path("/api/v1/self-order-access/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
        .filter(tenantFilter)
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
   * ({@link #OPS_ROLES}) route — {@code @Order(HIGHEST_PRECEDENCE)} makes the specific match win,
   * so an {@code employee}/{@code cashier} login is not 403'd reading its own page grants.
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
        .filter(TenantContextHeaderFilter.tenantOptional())
        .build();
  }

  /**
   * {@code GET /api/v1/org-units} (EXACT flat list) — READ the org-unit structure, allowed for
   * every office role ({@link #ORG_READ_ROLES}), not just owner/manager. The HR/People area and
   * per-unit reports need unit names to scope employees, payroll, and P&amp;L; reading structure is
   * not managing it. {@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: this GET carve-out must be
   * matched before the general (all-methods) {@link #orgUnitsRoute} so an {@code hr}/{@code
   * accountant} GET of the list is admitted; write verbs and — crucially — every {@code
   * /api/v1/org-units/{id}/...} sub-resource (notably the {@code device-credential} reveal and
   * {@code /users} staffing) do NOT match this exact path and fall through to that OPS-only route.
   * The path is exact, NOT {@code /**}, precisely to keep those sensitive siblings
   * owner/manager-only (see {@link #ORG_READ_ROLES}).
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> orgUnitsReadRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("org-service-org-units-read")
        .route(GET("/api/v1/org-units"), http())
        .before(uri(routes.orgService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(ORG_READ_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
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

  /**
   * {@code POST /api/v1/menu/images/migrate} — the owner-triggered, per-tenant conversion of legacy
   * inline base64 menu images to the object store (ADR 0048). OWNER_ROLES: a bulk data-migration
   * action, not a POS operation. {@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: the general
   * {@code /menu/**} POS route below would otherwise swallow this path and admit cashiers — the
   * {@link #userMeOutletsRoute} first-match-wins pattern.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> menuImageMigrateRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-menu-image-migrate")
        .route(path("/api/v1/menu/images/migrate"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
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

  /**
   * Register sessions — closing kasir (ADR 0036): the cashier opens/closes the till, so the route
   * carries POS_ROLES (owner, manager, cashier), like every till surface.
   */
  @Bean
  RouterFunction<ServerResponse> registerSessionsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-register-sessions")
        .route(path("/api/v1/register-sessions/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Inventory stocktake at the daily close (ADR 0038 phase 3): the operator counts stock at the
   * outlet, so the route carries POS_ROLES (owner, manager, cashier), like every till surface.
   */
  @Bean
  RouterFunction<ServerResponse> stocktakesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-stocktakes")
        .route(path("/api/v1/stocktakes/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Ingredient catalog (ADR 0046 phase 1): the raw-material (bahan) list an outlet counts at a
   * stock opname. Same POS_ROLES surface as {@link #menuRoute} — cashiers manage/receive stock at
   * the till.
   */
  @Bean
  RouterFunction<ServerResponse> ingredientsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-ingredients")
        .route(path("/api/v1/ingredients/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Ingredient stock opname (ADR 0046 phase 1): the physical count of the ingredient catalog, the
   * successor subject to {@link #stocktakesRoute}'s legacy menu-item count. Same POS_ROLES surface
   * as every till surface — the operator counts stock at the outlet.
   */
  @Bean
  RouterFunction<ServerResponse> ingredientStocktakesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-ingredient-stocktakes")
        .route(path("/api/v1/ingredient-stocktakes/**"), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Company-managed sales channels + the ONLINE tender (ADR 0036 Phase B2): a cashier LISTs the
   * channel picker at checkout (GET), while CREATE/PATCH are further gated to owner/manager
   * SERVICE-SIDE by {@code SalesChannelWriter} — the route itself carries POS_ROLES (owner,
   * manager, cashier), like every till surface, since a cashier legitimately needs to read the list
   * even though it cannot mutate it.
   */
  @Bean
  RouterFunction<ServerResponse> salesChannelsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-sales-channels")
        .route(path("/api/v1/sales-channels/**"), http())
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

  /**
   * The two money-OUT reversal sub-paths — {@code POST /api/v1/payments/*}{@code /refund} and
   * {@code /void} — escalated to {@link #SALE_REVERSAL_ROLES} (owner/manager). A refund returns
   * money to a customer and drives the finance GL reversal (ADR 0006/0061); a {@code
   * cashier}/{@code chef}/{@code waitress} rings sales but never reverses one.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: RouterFunction beans match in order
   * (first match wins, NOT most-specific wins), so this specific route must precede the general
   * {@link #paymentsRoute} ({@code /payments/**}, POS_ROLES) below — otherwise the wildcard route
   * would swallow {@code /refund} and let a cashier reverse a sale. Every OTHER {@code
   * /payments/**} path (capture, receipt) falls through to that POS_ROLES route unchanged — same
   * first-match pattern as {@link #userMeOutletsRoute} and the owner-only payroll bank-file route.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> paymentReversalRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("restaurant-service-payment-reversal")
        .route(path("/api/v1/payments/*/refund").or(path("/api/v1/payments/*/void")), http())
        .before(uri(routes.restaurantService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(SALE_REVERSAL_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
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
        .filter(new RoleAuthorizationFilter(OPS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  // ---------------------------------------------------------------------------
  // payment-service (QRIS payment modes + PSP charges — ADR 0045)
  // ---------------------------------------------------------------------------

  /**
   * The PUBLIC Midtrans settlement webhook ({@code /api/v1/psp-webhooks/**}) — the fleet's third
   * ANONYMOUS route (after {@link #signupRoute} and {@link #selfOrderRoute}, whose recipe this
   * copies exactly): Midtrans's notification servers carry no JWT, so there is no role check and no
   * {@link TenantContextHeaderFilter}. payment-service owns ALL authentication once the request
   * arrives (provisional tenant bind from the callback URL's {@code companyId} + RLS-scoped loads +
   * a constant-time signature check against the merchant's own server key — ADR 0045).
   *
   * <p>{@link AnonymousRateLimitFilter} meters per client IP in its OWN Redis namespace ({@code
   * anon:psp-webhook:}), fail-closed like every bucket; {@link AnonymousTenantHeaderStripFilter}
   * unconditionally removes any client-supplied {@code X-Company-Id}/{@code X-Actor}/{@code
   * X-Roles} so an anonymous caller can never inject a trusted tenant header.
   */
  @Bean
  RouterFunction<ServerResponse> pspWebhookRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      RateLimitProperties rateLimits) {
    return GatewayRouterFunctions.route("payment-service-psp-webhook")
        .route(path("/api/v1/psp-webhooks/**"), http())
        .before(uri(routes.paymentService()))
        .filter(new AnonymousRateLimitFilter(limiter, rateLimits.pspWebhook(), "anon:psp-webhook:"))
        .filter(new AnonymousTenantHeaderStripFilter())
        .build();
  }

  // ---------------------------------------------------------------------------
  // object store (public menu-image serving — ADR 0048)
  // ---------------------------------------------------------------------------

  /**
   * Public media proxy — {@code GET /api/media/restaurant/**} rewritten onto the MinIO bucket
   * ({@code /native-media/restaurant/…}) and forwarded to the object store itself (ADR 0048), the
   * gateway's only non-service target. ANONYMOUS by design: menu images already reach anonymous
   * diners through self-order (ADR 0029), an {@code <img>} tag cannot carry a bearer token, and the
   * offline till caches image URLs in its service worker — so the protection model is
   * unguessability (256-bit content-hash keys) + tenant-scoped prefixes, not authentication.
   *
   * <p>Deliberately NARROW: only the {@code restaurant/} prefix is routed (matching the only
   * anonymous-download prefix {@code docker/minio/init.sh} grants). {@code employee/} (expense
   * receipts) and {@code payment/} (QRIS) objects are NOT reachable here — no route matches them,
   * the edge 401s them ({@code SecurityConfig} opens only this exact prefix), and MinIO itself
   * would deny anonymous reads on those prefixes (defense in depth, three layers). GET-only: the
   * store is written exclusively by the owning services with their prefix-scoped credentials.
   *
   * <p>{@link AnonymousRateLimitFilter} meters per client IP in its OWN namespace ({@code
   * anon:media:}, loose — a first menu render bursts a whole grid of image GETs); {@link
   * AnonymousTenantHeaderStripFilter} strips any client-supplied trusted headers as on every
   * anonymous route. The after-filter pins cache semantics: content-hash keys are immutable, so a
   * hit caches for a year ({@code immutable}); anything else (403/404 from MinIO) is {@code
   * no-store} so a miss or a denied probe is never cached. {@code X-Content-Type-Options: nosniff}
   * matches the authenticated blob endpoints' posture.
   */
  @Bean
  RouterFunction<ServerResponse> mediaRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      RateLimitProperties rateLimits) {
    return GatewayRouterFunctions.route("media")
        .route(GET("/api/media/restaurant/**"), http())
        // COUPLING (review N1): the bucket segment here must match the services'
        // native.media.bucket (MEDIA_BUCKET, default native-media) — the fleet does not
        // override it anywhere; if that ever changes, externalize this rewrite too.
        .before(rewritePath("/api/media/(?<key>.*)", "/native-media/${key}"))
        .before(uri(routes.media()))
        .filter(new AnonymousRateLimitFilter(limiter, rateLimits.media(), "anon:media:"))
        .filter(new AnonymousTenantHeaderStripFilter())
        .after(
            (request, response) -> {
              if (response.statusCode().is2xxSuccessful()) {
                response
                    .headers()
                    .set(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
              } else {
                response.headers().set(HttpHeaders.CACHE_CONTROL, "no-store");
              }
              response.headers().set("X-Content-Type-Options", "nosniff");
              return response;
            })
        .build();
  }

  /**
   * {@code GET /api/v1/payment-settings/effective} — the till reading its outlet's effective QRIS
   * mode/availability at tender time (ADR 0045). POS_ROLES: the cashier is the primary consumer.
   * The response is availability only (mode + booleans) — never key material.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing: the general owner-only {@code
   * /payment-settings/**} route below would otherwise swallow this exact path and 403 the cashier —
   * the {@link #userMeOutletsRoute} first-match-wins pattern.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> paymentSettingsEffectiveRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("payment-service-settings-effective")
        .route(path("/api/v1/payment-settings/effective"), http())
        .before(uri(routes.paymentService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/payment-settings/static-qr/image} — the till fetching the merchant's static
   * QRIS image blob to show the customer (ADR 0045). POS_ROLES for the same reason as {@link
   * #paymentSettingsEffectiveRoute}, and {@code @Order(HIGHEST_PRECEDENCE)} for the same
   * first-match-wins reason (the owner-only {@code /payment-settings/**} route would 403 the
   * cashier's image fetch).
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> paymentSettingsStaticQrImageRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("payment-service-settings-static-qr-image")
        .route(path("/api/v1/payment-settings/static-qr/image"), http())
        .before(uri(routes.paymentService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * QRIS payment-settings administration ({@code /api/v1/payment-settings/**}) — OWNER-ONLY, the
   * {@link #payrollRunBankFileRoute} sensitivity class: this surface stores the merchant's own
   * Midtrans server key (write-only, encrypted at rest) and decides how money is taken at the till,
   * so it is narrower than every other capability array (no {@code manager}, {@code hr}, {@code
   * accountant}, {@code chef}, or {@code waitress}). The two POS reads above are carved out first;
   * everything else here (mode upserts, credential saves, image uploads) is the owner's alone —
   * enforced a second time service-side (ADR 0045).
   */
  @Bean
  RouterFunction<ServerResponse> paymentSettingsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("payment-service-settings")
        .route(path("/api/v1/payment-settings/**"), http())
        .before(uri(routes.paymentService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Dynamic-QRIS charges ({@code /api/v1/payment-charges/**}) — POS_ROLES, like every till surface
   * (ADR 0045): the cashier creates the charge for its own PENDING payment, polls the QR, and
   * syncs/cancels. The money-capture path is NOT here — it runs through the vertical's idempotent
   * consumer, and the consumer independently verifies amount/currency, so this surface cannot move
   * money on its own.
   */
  @Bean
  RouterFunction<ServerResponse> paymentChargesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("payment-service-charges")
        .route(path("/api/v1/payment-charges/**"), http())
        .before(uri(routes.paymentService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
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
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * The till's operator sign-in ({@code /api/v1/operators/**}, ADR 0049 P1) — POS_ROLES, like every
   * other till surface ({@link #salesRoute}, {@link #menuRoute}): a cashier is exactly who needs
   * {@code POST /api/v1/operators/session} to identify themselves at a shared terminal. A FRESH
   * path prefix (no overlap with {@link #employeesRoute}'s {@code /api/v1/employees/**}, the
   * owner/manager-only surface the PIN set/reset endpoint rides instead), so no {@code @Order}
   * precedence game is needed here.
   */
  @Bean
  RouterFunction<ServerResponse> operatorsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-operators")
        .route(path("/api/v1/operators/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(POS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/payroll-runs/{runId}/bank-file} — the net-pay bank file (Track P phase P5):
   * decrypted bank-account PII, OWNER-ONLY (narrower than {@link #payrollRunsRoute}'s {@link
   * #PAYROLL_ROLES}, which also admits {@code hr}).
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing — the same {@code
   * userMeOutletsRoute}/{@code currentCompanyRoute} pattern: RouterFunction beans are matched in
   * declaration order across the WHOLE bean set (first match wins, NOT most-specific-path wins), so
   * without this the general {@code /api/v1/payroll-runs/**} route below ({@link #PAYROLL_ROLES})
   * would swallow this exact path FIRST and let an {@code hr} token through — this route must be
   * checked before that one for its narrower OWNER_ROLES gate to apply. The route path pattern
   * carries a single-path-SEGMENT wildcard between {@code payroll-runs} and {@code bank-file} (one
   * {@code *}, not {@code **}), matching only {@code /payroll-runs/{runId}/bank-file}, never a
   * longer sub-path, so it cannot accidentally shadow any OTHER {@code /payroll-runs/**} path
   * (payslips, allocations, the run list) — those still fall through to {@link #payrollRunsRoute}
   * untouched.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> payrollRunBankFileRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-run-bank-file")
        .route(path("/api/v1/payroll-runs/*/bank-file"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/payroll-runs/{runId}/payslips/{employeeId}/authorized} — the payslip with
   * REAL (decrypted) amounts, for printing (Track P phase P9). OWNER-ONLY, the same bank-file
   * precedent as {@link #payrollRunBankFileRoute}: salary is PII, so only the narrower owner role
   * gets the decrypted read — a manager still lands on the masked {@code
   * /payroll-runs/*&#47;payslips/*} route below via {@link #payrollRunsRoute}.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} for the same reason as every other exact-path carve-out
   * here: the general {@code /api/v1/payroll-runs/**} route ({@link #PAYROLL_ROLES}) would
   * otherwise match first and let an {@code hr} token through. Two path-segment wildcards ({@code
   * *}, never {@code **}) match only this exact sub-path, so it cannot shadow the masked payslip
   * route, the bank-file route, or any other {@code /payroll-runs/**} path.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> payrollRunPayslipAuthorizedRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-run-payslip-authorized")
        .route(path("/api/v1/payroll-runs/*/payslips/*/authorized"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
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
        .filter(new RoleAuthorizationFilter(PAYROLL_ROLES))
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
        .filter(new RoleAuthorizationFilter(PAYROLL_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/payroll-reports/1721a1} — the annual per-employee Bukti Potong 1721-A1 CSV
   * (Track P phase P9). OWNER-ONLY: NIK/NPWP PII, the exact bank-file precedent {@link
   * #payrollRunBankFileRoute}'s javadoc already anticipated this route for.
   *
   * <p>{@code @Order(HIGHEST_PRECEDENCE)} so this exact path is checked before the general {@code
   * /api/v1/payroll-reports/**} {@link #PAYROLL_ROLES} route below — the same first-match-wins
   * pattern as every other narrow-owner-only carve-out in this class.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> payrollReportBukti1721A1Route(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-report-1721a1")
        .route(path("/api/v1/payroll-reports/1721a1"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * {@code GET /api/v1/payroll-reports/bpjs-summary} — the per-employee, per-program BPJS
   * contribution CSV (Track P phase P9). OWNER-ONLY by judgment call: it is not the NIK/NPWP class
   * of PII {@code 1721a1} carries, but a named employee's BPJS wage is, in practice, close to their
   * base pay — salary-revealing in the same spirit as rule 6 (see {@code PayrollReportReader}'s
   * class javadoc for the full reasoning), so it is gated exactly like {@code 1721a1} rather than
   * the plain owner/manager {@code pph21-monthly} aggregate below.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  RouterFunction<ServerResponse> payrollReportBpjsSummaryRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-report-bpjs-summary")
        .route(path("/api/v1/payroll-reports/bpjs-summary"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(OWNER_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * The remaining statutory report(s) under {@code /api/v1/payroll-reports/**} — today just {@code
   * GET /api/v1/payroll-reports/pph21-monthly} (Track P phase P9), the AGGREGATE-only SPT Masa
   * summary (no per-employee figure) — {@code owner}/{@code hr} ({@link #PAYROLL_ROLES}), same as
   * every other payroll read. {@code manager} is deliberately EXCLUDED here (preset role-based
   * access model Phase 1 — payroll is not an operations concern). The two narrower {@link
   * #OWNER_ROLES} carve-outs above are checked first.
   */
  @Bean
  RouterFunction<ServerResponse> payrollReportsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-payroll-reports")
        .route(path("/api/v1/payroll-reports/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(PAYROLL_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Expense claims — the manager/owner decision surface ({@code /api/v1/expense-claims/**}, ADR
   * 0030 Phase E1): the tenant-wide claim list plus approve/refuse. Owner/manager only; the
   * employee's own self-service half rides {@code /api/v1/me/expense-claims/**} on {@link #meRoute}
   * (every business role, resolved strictly from the caller's own {@code X-Actor}).
   */
  @Bean
  RouterFunction<ServerResponse> expenseClaimsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-expense-claims")
        .route(path("/api/v1/expense-claims/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Expense-category admin catalog ({@code /api/v1/expense-categories/**}, ADR 0030 Phase E1) —
   * owner/manager only: the category set drives finance's expense-account mapping, config an
   * employee never touches directly.
   */
  @Bean
  RouterFunction<ServerResponse> expenseCategoriesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-expense-categories")
        .route(path("/api/v1/expense-categories/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Leave requests — the manager/owner decision surface ({@code /api/v1/leave-requests/**}, ADR
   * 0033 Track P Phase P6): the tenant-wide list plus approve/reject. Owner/manager only; the
   * employee's own self-service half rides {@code /api/v1/me/leave-requests/**} on {@link #meRoute}
   * (every business role, resolved strictly from the caller's own {@code X-Actor}).
   */
  @Bean
  RouterFunction<ServerResponse> leaveRequestsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-leave-requests")
        .route(path("/api/v1/leave-requests/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Overtime entries — the manager/owner decision surface ({@code /api/v1/overtime-entries/**}, ADR
   * 0033 Track P Phase P6), mirroring {@link #leaveRequestsRoute}.
   */
  @Bean
  RouterFunction<ServerResponse> overtimeEntriesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-overtime-entries")
        .route(path("/api/v1/overtime-entries/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * The tenant's single work-calendar row ({@code /api/v1/work-calendar/**}, ADR 0033 §6 Track P
   * Phase P6) — owner/manager only: the divisor/days-per-week config feeds unpaid-leave pay math
   * (Track P Phase P7), never an employee-editable setting.
   */
  @Bean
  RouterFunction<ServerResponse> workCalendarRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-work-calendar")
        .route(path("/api/v1/work-calendar/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Per-employee derived leave balances + adjustments ({@code /api/v1/leave-balances/**}, ADR 0033
   * §4 Track P Phase P6) — owner/manager only; the employee's own balance rides {@code
   * /api/v1/me/leave-balance} on {@link #meRoute}.
   */
  @Bean
  RouterFunction<ServerResponse> leaveBalancesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("employee-service-leave-balances")
        .route(path("/api/v1/leave-balances/**"), http())
        .before(uri(routes.employeeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(HR_ROLES))
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
        .filter(new RoleAuthorizationFilter(REPORTS_ROLES))
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
        .filter(new RoleAuthorizationFilter(REPORTS_ROLES))
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
        .filter(new RoleAuthorizationFilter(REPORTS_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Platform channel settlements (ADR 0036 Phase C) — owner/manager only: recording a payout posts
   * money (Dr cash-clearing + Dr platform-fee / Cr platform-receivable) and decrements the
   * per-channel outstanding balance, a back-office finance action with no cashier surface.
   */
  @Bean
  RouterFunction<ServerResponse> platformSettlementsRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-platform-settlements")
        .route(path("/api/v1/platform-settlements/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Opening balances & business migration (ADR 0037) — owner/manager only: recording a company's
   * opening balance sheet posts money (Dr assets / Cr liabilities + equity), a back-office finance
   * action with no cashier surface.
   */
  @Bean
  RouterFunction<ServerResponse> openingBalancesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-opening-balances")
        .route(path("/api/v1/opening-balances/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
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
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
        .filter(tenantFilter)
        .build();
  }

  /**
   * Payroll liabilities (finance-service) — owner/manager only. {@code
   * /api/v1/payroll-liabilities/**} covers the period's liability-bucket read + the settlement
   * write ({@code POST .../{runLedgerId}/settlements}) (ADR 0032, Track P phase P5). Fresh prefix —
   * no collision.
   */
  @Bean
  RouterFunction<ServerResponse> payrollLiabilitiesRoute(
      GatewayRouteProperties routes,
      RedisTokenBucketRateLimiter limiter,
      TenantContextHeaderFilter tenantFilter) {
    return GatewayRouterFunctions.route("finance-service-payroll-liabilities")
        .route(path("/api/v1/payroll-liabilities/**"), http())
        .before(uri(routes.financeService()))
        .filter(new RateLimitFilter(limiter))
        .filter(new RoleAuthorizationFilter(FINANCE_ROLES))
        .filter(tenantFilter)
        .build();
  }
}
