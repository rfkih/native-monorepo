package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.EntitlementService;
import id.co.nativeapp.entitlement.entitlement.TenantEntitlement;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (d) — the cross-tenant isolation proof, relying on AUTO-applied RLS.
 *
 * <p>An entitlement granted under tenant A is invisible to tenant B. The grant binds A's {@code
 * company_id} as the tenant; the list read ({@link EntitlementService#list()}) carries NO {@code
 * WHERE company_id} and never calls the synchronizer by hand — only the auto-RLS aspect sets the
 * tenant GUC on each {@code @Transactional} unit of work. A list as B sees nothing of A's
 * entitlement (rule 5).
 *
 * <p>Runs as the unprivileged {@code app_user} role; {@code FORCE ROW LEVEL SECURITY} in the
 * baseline binds even that owning role.
 */
@SpringBootTest
class EntitlementTenancyIsolationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "owner-a@example.co.id";
  private static final String ACTOR_B = "owner-b@example.co.id";

  @Autowired private EntitlementService entitlementService;

  @Test
  void anEntitlementGrantedUnderTenantAIsInvisibleToTenantB() throws Exception {
    // Grant a module for A only (the service binds A as the tenant via callAs).
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          entitlementService.grant("restaurant");
          return null;
        });

    // As A, the entitlement is visible.
    List<TenantEntitlement> aView =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> entitlementService.list());
    assertThat(aView).extracting(TenantEntitlement::getModuleKey).containsExactly("restaurant");

    // As B, the list is empty — A's row is invisible via RLS (no WHERE company_id is written).
    List<TenantEntitlement> bView =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> entitlementService.list());
    assertThat(bView).isEmpty();
  }
}
