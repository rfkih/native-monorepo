package id.co.nativeapp.entitlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for entitlement-service — the platform service that owns which modules each company
 * has, and billing lines (ARCHITECTURE.md §2).
 *
 * <p>It consumes {@code CompanyCreated} to grant a new company its DEFAULT set of entitlements,
 * supports grant/revoke of a module (emitting {@code EntitlementGranted}/{@code EntitlementRevoked}
 * via the transactional outbox), and provides the DB-backed loader behind the shared {@code
 * libs/entitlement-check} Redis cache (which it seeds/invalidates from its own events).
 *
 * <p><strong>No copied platform config.</strong> The cross-cutting persistence wiring (Spring Data
 * JPA auditing + the auto-RLS beans/aspect with the pinned tx-advisor order) is contributed by
 * {@code libs/tenant}'s {@code TenantRlsAutoConfiguration}, and the shared RFC-7807 {@code
 * ProblemDetail} advice + local JWT validation by {@code libs/security} — both Spring Boot
 * auto-configurations this service inherits purely by depending on those libraries, with NO copied
 * {@code RlsConfig}/{@code RlsAutoApplyAspect}/{@code PersistenceConfig}/{@code
 * ApiExceptionHandler}. The only config this service declares is service-specific: the
 * outbox/dedupe + cache wiring ({@code EventsConfig}), the Kafka consumer wiring ({@code
 * KafkaConfig}), and the dev tenant filter ({@code DevTenantFilter}) — mirroring how
 * finance-service declares its deps after the consolidation.
 */
@SpringBootApplication
public class EntitlementServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(EntitlementServiceApplication.class, args);
  }
}
