package id.co.nativeapp.gateway.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * The gateway's RS256 {@link JwtDecoder}, built explicitly so its <strong>clock skew</strong> is a
 * tight, configurable value rather than Spring Security's generous 60-second default.
 *
 * <p>The decoder validates the signature against Keycloak's JWKS ({@code jwk-set-uri}) and runs an
 * issuer check ({@code iss} must equal the trusted realm issuer, when configured) plus a timestamp
 * check whose leeway is {@code native.gateway.jwt.clock-skew} (default {@code 5s}). A small skew
 * matters at a security edge: an expired token should be rejected promptly — a 60s grace would let
 * a stale token keep working for a full minute — and it keeps the expired-token {@code 401} proof
 * deterministic. The skew is externalized so an environment with real clock drift can widen it
 * without a code change (ENGINEERING-STANDARDS §6/§7).
 *
 * <p>The JWKS fetch (the gateway's single sanctioned sync edge) runs on a {@link RestTemplate} with
 * EXPLICIT connect/read timeouts ({@code native.gateway.jwt.jwks-connect-timeout} and {@code
 * native.gateway.jwt.jwks-read-timeout}, both default {@code 500ms}) — §4. Spring Security already
 * bounds this fetch to {@code 500ms}/{@code 500ms} by default ({@code
 * RestTemplateWithNimbusDefaultTimeouts}); surfacing it here makes the timeout explicit,
 * externalized (a slow-Keycloak environment can widen it with no code change), and asserted at boot
 * — never a back-door loosening. The constructor fails fast if either is non-positive (the
 * gateway's outbound-client startup self-check; the gateway is the documented carve-out that does
 * not depend on libs/security's {@code OutboundClientTimeoutCheck}).
 */
@Configuration
public class JwtDecoderConfig {

  private final Duration clockSkew;
  private final Duration jwksConnectTimeout;
  private final Duration jwksReadTimeout;

  public JwtDecoderConfig(
      @Value("${native.gateway.jwt.clock-skew:5s}") Duration clockSkew,
      @Value("${native.gateway.jwt.jwks-connect-timeout:500ms}") Duration jwksConnectTimeout,
      @Value("${native.gateway.jwt.jwks-read-timeout:500ms}") Duration jwksReadTimeout) {
    this.clockSkew = clockSkew;
    this.jwksConnectTimeout =
        requirePositive("native.gateway.jwt.jwks-connect-timeout", jwksConnectTimeout);
    this.jwksReadTimeout = requirePositive("native.gateway.jwt.jwks-read-timeout", jwksReadTimeout);
  }

  @Bean
  JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
    OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();

    SimpleClientHttpRequestFactory jwksRequestFactory = new SimpleClientHttpRequestFactory();
    jwksRequestFactory.setConnectTimeout(jwksConnectTimeout);
    jwksRequestFactory.setReadTimeout(jwksReadTimeout);

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(jwksRequestFactory))
            .build();

    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(clockSkew));
    if (jwt.getIssuerUri() != null) {
      validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
    }
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }

  /** Fails fast at startup if a JWKS timeout is null, zero, or negative (§4 — never infinite). */
  private static Duration requirePositive(String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(
          name
              + " must be a positive duration — the JWKS fetch must never use an infinite/zero"
              + " timeout (ENGINEERING-STANDARDS §4); got "
              + value);
    }
    return value;
  }
}
