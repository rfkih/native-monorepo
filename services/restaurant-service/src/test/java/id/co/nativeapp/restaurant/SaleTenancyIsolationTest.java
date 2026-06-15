package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance (c) — the tenancy isolation proof, relying on AUTO-applied RLS.
 *
 * <p>A sale recorded under tenant A is invisible to tenant B. The read path ({@link
 * SaleService#findAllForCurrentTenant()}) carries NO {@code WHERE company_id} and never calls the
 * synchronizer by hand — only {@link RlsAutoApplyAspect} sets the tenant GUC on each
 * {@code @Transactional} unit of work. This models a developer who forgets the tenant and proves
 * they are still protected (rule 5).
 *
 * <p>Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} in the baseline binds even that owning role.
 */
@SpringBootTest
class SaleTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a";
  private static final String ACTOR_B = "cashier-b";

  @Autowired private SaleService saleService;

  @Test
  void aSaleRecordedUnderTenantAIsInvisibleToTenantB() throws Exception {
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    // Record one sale for A and one for B, each in its own tenant scope.
    UUID saleA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                saleService
                    .recordSale(
                        new RecordSaleCommand(businessId, 1_000_000L, "IDR", occurredAt, "a-key"))
                    .sale()
                    .getId());
    UUID saleB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                saleService
                    .recordSale(
                        new RecordSaleCommand(businessId, 2_000_000L, "IDR", occurredAt, "b-key"))
                    .sale()
                    .getId());

    // Inside A's scope: only A's sale is visible. No WHERE clause anywhere; RLS only.
    List<UUID> aVisible =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> saleService.findAllForCurrentTenant().stream().map(Sale::getId).toList());
    assertThat(aVisible).containsExactly(saleA);
    assertThat(aVisible).doesNotContain(saleB);

    // The inverse: inside B's scope only B's sale is visible.
    List<UUID> bVisible =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () -> saleService.findAllForCurrentTenant().stream().map(Sale::getId).toList());
    assertThat(bVisible).containsExactly(saleB);
    assertThat(bVisible).doesNotContain(saleA);
  }
}
