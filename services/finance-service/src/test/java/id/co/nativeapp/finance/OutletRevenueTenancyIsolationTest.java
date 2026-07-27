package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.service.OutletRevenueReader;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant isolation of the {@code outlet_revenue} read model, relying on AUTO-applied RLS.
 *
 * <p>Outlet revenue posted under tenant A is invisible to tenant B: tenant B's outlet list for the
 * period is empty. The posting binds the event's {@code company_id} as the tenant; the read ({@link
 * OutletRevenueReader#outletsForPeriod}) carries NO {@code WHERE company_id} and never calls the
 * synchronizer by hand — only the auto-RLS aspect sets the tenant GUC on each
 * {@code @Transactional} unit of work. A query as B sees no data (rule 5). Runs as the unprivileged
 * {@code app_user} role; {@code FORCE ROW LEVEL SECURITY} on {@code outlet_revenue} binds even that
 * owning role.
 */
@SpringBootTest
class OutletRevenueTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-aaaaaaaaaaaa";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-bbbbbbbbbbbb";
  private static final UUID OUTLET_A = UUID.fromString("fa000000-0000-0000-0000-000000000001");
  private static final String ACTOR_B = "viewer-b@isolation.co.id";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private OutletRevenueReader outletRevenueReader;

  @Test
  void outletRevenuePostedUnderTenantAIsInvisibleToTenantB() throws Exception {
    Instant occurredAt = Instant.parse("2026-07-01T08:30:00Z");
    String period = "2026-07";

    // Post revenue for tenant A only.
    boolean posted =
        revenuePostingService.handle(
            new SaleRecordedEvent(
                UUID.randomUUID(), TENANT_A, OUTLET_A, Money.ofMinor(500_000L, "IDR"), occurredAt));
    assertThat(posted).isTrue();

    // As B, the outlet list for the period is empty — A's row is invisible via RLS.
    Optional<OutletRevenueResponse> bView =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> outletRevenueReader.outletsForPeriod(period));
    assertThat(bView)
        .as("tenant B must see no outlet data (tenant A's rows are invisible via RLS)")
        .isEmpty();
  }
}
