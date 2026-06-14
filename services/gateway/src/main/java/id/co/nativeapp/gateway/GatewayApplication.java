package id.co.nativeapp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the gateway (M1.1) — the single synchronous edge of Native.
 *
 * <p>Stateless: no database, no JPA, no RLS. It validates inbound RS256 JWTs against Keycloak's
 * JWKS, injects the validated tenant context downstream as trusted headers, routes {@code
 * /api/v1/**} to the owning service, and applies a Redis-backed per-tenant rate limit.
 */
@SpringBootApplication
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
