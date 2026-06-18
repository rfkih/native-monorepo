package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.WashResponse;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (e) — the tenancy isolation proof, relying on AUTO-applied RLS.
 *
 * <p>A wash recorded under tenant A is invisible to tenant B. The read path ({@link
 * WashService#findAllForCurrentTenant()}) carries NO {@code WHERE company_id} and never calls the
 * synchronizer by hand — only {@link RlsAutoApplyAspect} sets the tenant GUC on each
 * {@code @Transactional} unit of work. This models a developer who forgets the tenant and proves
 * they are still protected (rule 5). Both tenants are granted carwash first so the entitlement gate
 * lets each record a wash.
 *
 * <p>Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} in the baseline binds even that owning role.
 */
@SpringBootTest
class WashTenancyIsolationTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "attendant-a";
  private static final String ACTOR_B = "attendant-b";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void aWashRecordedUnderTenantAIsInvisibleToTenantB() throws Exception {
    grantCarwash(TENANT_A);
    grantCarwash(TENANT_B);

    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    UUID washA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                washService
                    .recordWash(
                        new RecordWashCommand(
                            OUTLET, "bay-1", 1_000_000L, "IDR", null, null, occurredAt, "a-key"))
                    .wash()
                    .id());
    UUID washB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                washService
                    .recordWash(
                        new RecordWashCommand(
                            OUTLET, "bay-2", 2_000_000L, "IDR", null, null, occurredAt, "b-key"))
                    .wash()
                    .id());

    // Inside A's scope: only A's wash is visible. No WHERE clause anywhere; RLS only.
    List<UUID> aVisible =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> washService.findAllForCurrentTenant().stream().map(WashResponse::id).toList());
    assertThat(aVisible).containsExactly(washA);
    assertThat(aVisible).doesNotContain(washB);

    // The inverse: inside B's scope only B's wash is visible.
    List<UUID> bVisible =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () -> washService.findAllForCurrentTenant().stream().map(WashResponse::id).toList());
    assertThat(bVisible).containsExactly(washB);
    assertThat(bVisible).doesNotContain(washA);
  }

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, "carwash", true));
  }
}
