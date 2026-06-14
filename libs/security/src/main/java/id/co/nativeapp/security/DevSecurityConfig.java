package id.co.nativeapp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The DEV-only security path. Active ONLY under the {@code dev} profile, it is the mutually
 * exclusive counterpart to {@link JwtSecurityConfig} ({@code @Profile("!dev")}): exactly one of the
 * two registers, so a service runs EITHER in dev (header-trust, no Keycloak) OR in non-dev (local
 * JWT validation).
 *
 * <p>Because {@code libs/security} puts Spring Security on the classpath, Spring Boot would
 * otherwise auto-apply its default {@code SecurityFilterChain} (which secures every request with
 * HTTP Basic / form login) even in dev — breaking the header-trust {@code DevTenantFilter} flow and
 * the existing web-slice / full-context tests that run under the dev profile. This chain therefore
 * <strong>permits all requests</strong> and disables CSRF/Basic/form login, deferring tenant
 * binding entirely to the existing {@code @Profile("dev")} {@code DevTenantFilter} (which trusts
 * the gateway-supplied {@code X-Company-Id}/{@code X-Actor} headers). It performs NO authentication
 * — which is exactly the documented dev contract: a forged header is acceptable in dev only, never
 * in production, where {@link JwtSecurityConfig} validates the JWT locally.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

  @Bean
  SecurityFilterChain devPermitAllSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        .build();
  }
}
