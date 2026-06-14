package id.co.nativeapp.gateway.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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
 */
@Configuration
public class JwtDecoderConfig {

  private final Duration clockSkew;

  public JwtDecoderConfig(@Value("${native.gateway.jwt.clock-skew:5s}") Duration clockSkew) {
    this.clockSkew = clockSkew;
  }

  @Bean
  JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
    OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();

    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(clockSkew));
    if (jwt.getIssuerUri() != null) {
      validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
    }
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }
}
