package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.service.PlatformReceivableWriter;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import id.co.nativeapp.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-tenant RLS regression for the two Phase C money tables (ADR 0036 review W3): tenant B must
 * see NOTHING of tenant A's per-channel receivable or settlement history, and a tenant-B settle
 * against tenant A's balance must fail the guarded decrement (RLS hides the row → 422) without
 * touching tenant A's money — the AssetTenancyIsolationTest pattern for the platform sub-ledger.
 */
@SpringBootTest
class PlatformTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-eeeeeeeeeeee";
  private static final String TENANT_B = "11111111-1111-1111-1111-ffffffffffff";
  private static final String CHANNEL = "GOFOOD";
  private static final String ACTOR = "isolation@integration.co.id";

  @Autowired private PlatformSettlementWriter settlementWriter;
  @Autowired private PlatformReceivableWriter receivableWriter;

  @Test
  void tenantBSeesNothingAndCannotSettleTenantAsBalance() throws Exception {
    // Tenant A accrues 100k and settles 40k of it.
    TenantContext.runAs(
        TENANT_A,
        ACTOR,
        () -> receivableWriter.accumulate(TENANT_A, CHANNEL, "IDR", 100_000L, ACTOR));
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> settlementWriter.settle(CHANNEL, 40_000L, 36_000L, "IDR", "iso-a-1"));

    // Tenant B: no outstanding rows, no history — RLS-scoped reads return empty, not A's rows.
    assertThat(TenantContext.callAs(TENANT_B, ACTOR, settlementWriter::outstanding)).isEmpty();
    assertThat(TenantContext.callAs(TENANT_B, ACTOR, () -> settlementWriter.history(null)))
        .isEmpty();

    // Tenant B settling A's channel: the guarded decrement sees no row (RLS) → 422, and A's
    // balance is untouched.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR,
                    () -> settlementWriter.settle(CHANNEL, 10_000L, 9_000L, "IDR", "iso-b-1")))
        .isInstanceOf(PlatformOverSettlementException.class);

    assertThat(
            TenantContext.callAs(TENANT_A, ACTOR, settlementWriter::outstanding)
                .getFirst()
                .outstandingMinor())
        .isEqualTo(60_000L);
    // A's history shows exactly its own single settlement.
    assertThat(TenantContext.callAs(TENANT_A, ACTOR, () -> settlementWriter.history(CHANNEL)))
        .hasSize(1);
  }

  @Test
  void sameIdempotencyKeyStringIsIndependentPerTenant() throws Exception {
    TenantContext.runAs(
        TENANT_A,
        ACTOR,
        () -> receivableWriter.accumulate(TENANT_A, CHANNEL, "IDR", 50_000L, ACTOR));
    TenantContext.runAs(
        TENANT_B,
        ACTOR,
        () -> receivableWriter.accumulate(TENANT_B, CHANNEL, "IDR", 50_000L, ACTOR));

    // The SAME key string settles independently under each tenant (uq is (company_id, key)) —
    // and neither replay-probes into the other's settlement.
    var a =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> settlementWriter.settle(CHANNEL, 20_000L, 18_000L, "IDR", "iso-shared"));
    var b =
        TenantContext.callAs(
            TENANT_B,
            ACTOR,
            () -> settlementWriter.settle(CHANNEL, 30_000L, 27_000L, "IDR", "iso-shared"));

    assertThat(a.created()).isTrue();
    assertThat(b.created()).isTrue();
    assertThat(
            TenantContext.callAs(TENANT_A, ACTOR, settlementWriter::outstanding)
                .getFirst()
                .outstandingMinor())
        .isEqualTo(30_000L);
    assertThat(
            TenantContext.callAs(TENANT_B, ACTOR, settlementWriter::outstanding)
                .getFirst()
                .outstandingMinor())
        .isEqualTo(20_000L);
  }
}
