package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.domain.EntitlementStatus;
import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import id.co.nativeapp.entitlement.entitlement.service.EntitlementService;
import id.co.nativeapp.entitlementcheck.CachedEntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Test (b) — grant then revoke a module updates the status + emits the events; and Test (c) — the
 * entitlement-check returns true after grant / false after revoke, with the Redis cache invalidated
 * by the change (a stale cache entry is cleared).
 *
 * <p>It exercises the API write path directly through {@link EntitlementService} (bound to a tenant
 * via {@link TenantContext#callAs}, exactly as the gateway/dev filter binds it at the request
 * edge), and the shared {@link CachedEntitlementChecker} that the three gates use. Runs as the
 * unprivileged {@code app_user} role so RLS genuinely engages, with a real Redis behind the
 * checker.
 */
@SpringBootTest
class GrantRevokeEntitlementCheckTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "owner-a@example.co.id";
  private static final String MODULE = "restaurant";

  @Autowired private EntitlementService entitlementService;
  @Autowired private CachedEntitlementChecker entitlementChecker;
  @Autowired private StringRedisTemplate redis;

  @Test
  void grantThenRevokeUpdatesTheStatusAndEmitsTheEvents() throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          TenantEntitlement granted = entitlementService.grant(MODULE);
          assertThat(granted.getStatus()).isEqualTo(EntitlementStatus.ENTITLED);

          TenantEntitlement revoked = entitlementService.revoke(MODULE);
          assertThat(revoked.getStatus()).isEqualTo(EntitlementStatus.REVOKED);
          assertThat(revoked.getRevokedAt()).isNotNull();
          return null;
        });

    // The single (company, module) row is now REVOKED (kept, not deleted), and one of each event
    // was
    // emitted to the outbox.
    assertThat(entitlementStatusAsAdmin(TENANT_A, MODULE)).isEqualTo("REVOKED");
    assertThat(entitlementRowCountAsAdmin(TENANT_A, MODULE)).isEqualTo(1L);
    assertThat(outboxCountAsAdmin("EntitlementGranted")).isEqualTo(1L);
    assertThat(outboxCountAsAdmin("EntitlementRevoked")).isEqualTo(1L);
  }

  @Test
  void theEntitlementCheckIsTrueAfterGrantFalseAfterRevokeAndTheCacheIsInvalidated()
      throws Exception {
    // Before any grant: the check is false, and that negative answer gets cached.
    assertThat(entitlementChecker.isEntitled(TENANT_A, MODULE)).isFalse();
    assertThat(cachedField(TENANT_A, MODULE)).isEqualTo("0"); // a stale "0" now sits in the cache

    // Grant the module via the API path. The service invalidates the company's cached view AFTER
    // the
    // grant commits, so the stale "0" is cleared — the next check re-reads the DB loader and is
    // true.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          entitlementService.grant(MODULE);
          return null;
        });
    assertThat(cachedField(TENANT_A, MODULE)).isNull(); // invalidation cleared the company hash
    assertThat(entitlementChecker.isEntitled(TENANT_A, MODULE)).isTrue();
    assertThat(cachedField(TENANT_A, MODULE)).isEqualTo("1"); // re-seeded as entitled

    // Revoke the module. Again the service invalidates the cache, so the stale "1" is cleared and
    // the
    // next check re-reads the DB loader and is false.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          entitlementService.revoke(MODULE);
          return null;
        });
    assertThat(cachedField(TENANT_A, MODULE)).isNull(); // invalidation cleared the company hash
    assertThat(entitlementChecker.isEntitled(TENANT_A, MODULE)).isFalse();
  }

  @Test
  void aDuplicateGrantOfTheSameModuleEmitsExactlyOneEntitlementGranted() throws Exception {
    // Grant the same module twice. The second grant is a no-op by aggregate state (already
    // ENTITLED):
    // it emits NO second EntitlementGranted (§1.3) and returns the existing entitlement.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          TenantEntitlement first = entitlementService.grant(MODULE);
          assertThat(first.getStatus()).isEqualTo(EntitlementStatus.ENTITLED);

          TenantEntitlement second = entitlementService.grant(MODULE);
          assertThat(second.getStatus()).isEqualTo(EntitlementStatus.ENTITLED);
          return null;
        });

    // One row (the upsert never doubles) and EXACTLY one EntitlementGranted outbox row — the
    // duplicate grant did not emit a second event.
    assertThat(entitlementRowCountAsAdmin(TENANT_A, MODULE)).isEqualTo(1L);
    assertThat(outboxCountAsAdmin("EntitlementGranted")).isEqualTo(1L);
  }

  @Test
  void aDuplicateRevokeOfTheSameModuleEmitsExactlyOneEntitlementRevoked() throws Exception {
    // Grant, then revoke the same module twice. The second revoke is a no-op by aggregate state
    // (already REVOKED): it emits NO second EntitlementRevoked (§1.3).
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          entitlementService.grant(MODULE);

          TenantEntitlement firstRevoke = entitlementService.revoke(MODULE);
          assertThat(firstRevoke.getStatus()).isEqualTo(EntitlementStatus.REVOKED);

          TenantEntitlement secondRevoke = entitlementService.revoke(MODULE);
          assertThat(secondRevoke.getStatus()).isEqualTo(EntitlementStatus.REVOKED);
          return null;
        });

    // The row is REVOKED, granted once and revoked once: exactly one of each event, the duplicate
    // revoke adding nothing.
    assertThat(entitlementStatusAsAdmin(TENANT_A, MODULE)).isEqualTo("REVOKED");
    assertThat(outboxCountAsAdmin("EntitlementGranted")).isEqualTo(1L);
    assertThat(outboxCountAsAdmin("EntitlementRevoked")).isEqualTo(1L);
  }

  /** The cached hash field for a (company, module), or null if not cached. */
  private String cachedField(String companyId, String moduleKey) {
    return redis.<String, String>opsForHash().get("entitlement:cache:" + companyId, moduleKey);
  }

  private String entitlementStatusAsAdmin(String companyId, String moduleKey) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT status FROM tenant_entitlement WHERE company_id = ? AND module_key = ?")) {
      ps.setString(1, companyId);
      ps.setString(2, moduleKey);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private long entitlementRowCountAsAdmin(String companyId, String moduleKey) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM tenant_entitlement WHERE company_id = ? AND module_key = ?")) {
      ps.setString(1, companyId);
      ps.setString(2, moduleKey);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long outboxCountAsAdmin(String eventType) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM outbox WHERE event_type = ?")) {
      ps.setString(1, eventType);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
