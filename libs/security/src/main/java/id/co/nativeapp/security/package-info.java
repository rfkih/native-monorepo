/**
 * Shared, defense-in-depth local JWT validation for every Native business service (#16).
 *
 * <p>ARCHITECTURE.md (Identity): <em>"Every service validates the signature locally (JWKS); never
 * trust the gateway alone."</em> Before this library, org/restaurant/finance bound the tenant
 * solely from the gateway-supplied {@code X-Company-Id} header via their {@code @Profile("dev")}
 * {@code DevTenantFilter} and did <strong>not</strong> validate the JWT locally — so a forged
 * {@code X-Company-Id} sent straight to a service port (bypassing the gateway) would bind an
 * arbitrary tenant and defeat row-level security (CLAUDE.md rule 5).
 *
 * <p>This package provides a Spring Boot <strong>auto-configuration</strong> ({@link
 * id.co.nativeapp.security.NativeSecurityAutoConfiguration}, registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}) so a service
 * gets local JWT validation simply by depending on {@code libs/security} — with <em>no copied
 * security code per service</em>. It ships two mutually-exclusive, profile-selected paths:
 *
 * <ul>
 *   <li><strong>non-dev (default)</strong> — {@link id.co.nativeapp.security.JwtSecurityConfig}
 *       registers a {@code SecurityFilterChain} that validates the inbound RS256 token against the
 *       configured Keycloak JWKS, requires a valid token for {@code /api/v1/**} (only {@code
 *       /healthz} and the narrow actuator probes are {@code permitAll}), and rejects a
 *       missing/invalid/expired token with {@code 401}. A {@link
 *       id.co.nativeapp.security.TenantBindingFilter} then binds {@link
 *       id.co.nativeapp.tenant.TenantContext} from the validated token's {@code company_id} claim
 *       and {@code sub}, so the RLS auto-apply aspect each service already wires sets {@code
 *       app.current_tenant}. A valid token with no {@code company_id} claim is rejected {@code 403}
 *       (RFC-7807). The inbound {@code X-Company-Id} header is never trusted — claims only.
 *   <li><strong>dev</strong> — {@link id.co.nativeapp.security.DevSecurityConfig} registers a
 *       permissive {@code SecurityFilterChain} so the existing header-trust {@code DevTenantFilter}
 *       remains the tenant source with no Keycloak required. The two paths cannot both be active:
 *       the JWT path is {@code @Profile("!dev")}, the dev path {@code @Profile("dev")}.
 * </ul>
 *
 * <p>The gateway is the edge resource server and deliberately does <strong>not</strong> depend on
 * this library.
 */
package id.co.nativeapp.security;
