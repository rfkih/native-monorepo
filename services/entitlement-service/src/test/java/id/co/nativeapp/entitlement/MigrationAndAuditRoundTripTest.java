package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import id.co.nativeapp.entitlement.entitlement.service.EntitlementService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Mandatory proof (ENGINEERING-STANDARDS §3.2): Flyway applies, Hibernate {@code ddl-auto=validate}
 * accepts the mapping against the migrated schema, the {@code module_catalog} seed is present, and
 * an {@code Auditable} entity round-trips with {@code company_id}/{@code created_by} populated from
 * the {@link TenantContext} scope.
 *
 * <p>The full context boots as the unprivileged {@code app_user}; the assertions confirm baseline +
 * mapping agree (a drift would fail the boot) and that the JPA-auditing pipeline stamps the actor.
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "owner-a@example.co.id";

  @Autowired private EntitlementService entitlementService;

  @Test
  void anEntitlementRoundTripsWithAuditColumnsStampedFromTheTenantScope() throws Exception {
    List<TenantEntitlement> persisted =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              entitlementService.grant("finance");
              return entitlementService.list();
            });

    assertThat(persisted).hasSize(1);
    TenantEntitlement entitlement = persisted.getFirst();
    assertThat(entitlement.getModuleKey()).isEqualTo("finance");
    assertThat(entitlement.getCompanyId()).isEqualTo(TENANT_A);
    assertThat(entitlement.getCreatedBy()).isEqualTo(ACTOR_A);
    assertThat(entitlement.getCreatedAt()).isNotNull();
    assertThat(entitlement.getUpdatedAt()).isNotNull();
  }
}
