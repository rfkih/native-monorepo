package id.co.nativeapp.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * The single auto-configuration entry point for shared, defense-in-depth local JWT validation
 * (#16). Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so any service
 * that puts {@code libs/security} on its classpath gets the behaviour with no copied code and no
 * {@code @Import} of its own.
 *
 * <p>It contributes two <strong>mutually exclusive</strong>, profile-selected security chains and
 * the tenant-binding filter that the non-dev chain installs:
 *
 * <ul>
 *   <li>{@link JwtSecurityConfig} ({@code @Profile("!dev")}) — the DEFAULT path: validate the
 *       inbound RS256 JWT against Keycloak's JWKS, secure {@code /api/v1/**}, and bind {@link
 *       id.co.nativeapp.tenant.TenantContext} from the token's {@code company_id} claim via {@link
 *       TenantBindingFilter} so RLS engages;
 *   <li>{@link DevSecurityConfig} ({@code @Profile("dev")}) — a permissive chain so the existing
 *       header-trust {@code DevTenantFilter} stays the tenant source with no Keycloak required.
 * </ul>
 *
 * <p>The {@code dev} profile selects exactly one of the two, so a service runs EITHER in dev
 * (header-trust, no Keycloak) OR in non-dev (local JWT validation). Toggle by activating the {@code
 * dev} Spring profile (e.g. {@code SPRING_PROFILES_ACTIVE=dev}); any other profile (or none) is the
 * secured production default.
 *
 * <p>It also binds {@link TenantOptionalProperties} ({@code native.security.tenant-optional}) so a
 * service can declare the narrow set of authenticated <em>bootstrap</em> endpoints (e.g. {@code
 * POST /api/v1/companies}) on which a token with no {@code company_id} claim must NOT be {@code
 * 403}'d — consumed by the non-dev {@link JwtSecurityConfig} when it installs {@link
 * TenantBindingFilter}.
 */
@AutoConfiguration
@EnableConfigurationProperties(TenantOptionalProperties.class)
@Import({JwtSecurityConfig.class, DevSecurityConfig.class})
public class NativeSecurityAutoConfiguration {}
