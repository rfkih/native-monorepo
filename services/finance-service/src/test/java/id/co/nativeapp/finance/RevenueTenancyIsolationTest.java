package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.RevenuePostingService;
import id.co.nativeapp.finance.revenue.RevenueReader;
import id.co.nativeapp.finance.revenue.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (c) — the tenancy isolation proof, relying on AUTO-applied RLS.
 *
 * <p>Revenue posted under tenant A is invisible to tenant B. The posting binds the event's {@code
 * company_id} as the tenant (the consumer path); the read ({@link RevenueReader#revenueForPeriod})
 * carries NO {@code WHERE company_id} and never calls the synchronizer by hand — only the auto-RLS
 * aspect sets the tenant GUC on each {@code @Transactional} unit of work. A query as B sees zero
 * (rule 5).
 *
 * <p>This posts directly through {@link RevenuePostingService} (rather than over Kafka) to keep the
 * isolation assertion deterministic; the Kafka transport is exercised by the consume + idempotency
 * tests. Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code
 * FORCE ROW LEVEL SECURITY} in the baseline binds even that owning role.
 */
@SpringBootTest
class RevenueTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_B = "viewer-b@example.co.id";

  @Autowired private RevenuePostingService postingService;
  @Autowired private RevenueReader revenueReader;

  @Test
  void revenuePostedUnderTenantAIsInvisibleToTenantB() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";

    // Post revenue for A only (the posting service binds A from the event's company_id).
    boolean posted =
        postingService.handle(
            new SaleRecordedEvent(
                UUID.randomUUID(),
                TENANT_A,
                BUSINESS,
                Money.ofMinor(1_000_000L, "IDR"),
                occurredAt));
    assertThat(posted).isTrue();

    // As B, the consolidated revenue for the period is empty — A's row is invisible via RLS.
    Optional<Money> bView =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> revenueReader.revenueForPeriod(period));
    assertThat(bView).isEmpty();
  }
}
