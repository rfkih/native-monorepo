package id.co.nativeapp.restaurant.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.pricing.dto.EffectiveRulesResponse;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
import id.co.nativeapp.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@code GET /api/v1/pricing/effective-rules} (Phase 5, ADR 0028), exercised
 * at the {@link TaxChargeService#resolveEffectiveRules} level (the controller is a 3-line pass-
 * through, same convention as {@code CheckoutAcceptanceTest} testing {@code OrderService}
 * directly).
 *
 * <p>{@code TENANT_A} is the demo tenant ({@code 11111111-...}) seeded with the V29 official {@code
 * PB1_RESTAURANT}/{@code SERVICE_CHARGE} rules (ADR 0042, superseding the V5 illustrative rows by
 * higher {@code rule_version}) — reused here rather than re-seeding, exactly like {@code
 * CheckoutAcceptanceTest}/{@code StockTrackingTest}. {@code TENANT_B} is a fresh tenant with no
 * seeded rule at all, proving both the no-rule fall-through AND that tenant A's rules never leak
 * across the RLS boundary.
 */
@SpringBootTest
class EffectiveRulesTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "d3d3d3d3-d3d3-d3d3-d3d3-d3d3d3d3d3d3";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";

  @Autowired private TaxChargeService taxChargeService;

  @Test
  void demoTenantResolvesTheSeededOfficialRules() throws Exception {
    EffectiveRulesResponse response =
        TenantContext.callAs(TENANT_A, ACTOR_A, taxChargeService::resolveEffectiveRules);

    assertThat(response.currency()).isEqualTo("IDR");
    assertThat(response.asOf()).isNotNull();
    assertThat(response.taxBp()).isEqualTo(1000L); // V29: PB1_RESTAURANT official 10% (ADR 0042)
    assertThat(response.taxRuleVersion()).isEqualTo("OFFICIAL-2026.1");
    assertThat(response.taxProvenance()).isEqualTo("OFFICIAL");
    assertThat(response.serviceChargeBp()).isEqualTo(500L); // V29: SERVICE_CHARGE official 5%
    assertThat(response.serviceChargeInTaxBase()).isTrue();
    assertThat(response.serviceChargeRuleVersion()).isEqualTo("OFFICIAL-2026.1");
    assertThat(response.serviceChargeProvenance()).isEqualTo("OFFICIAL");
  }

  @Test
  void freshTenantWithNoSeededRuleFallsThroughToZeros() throws Exception {
    EffectiveRulesResponse response =
        TenantContext.callAs(TENANT_B, ACTOR_B, taxChargeService::resolveEffectiveRules);

    assertThat(response.currency()).isNull();
    assertThat(response.taxBp()).isZero();
    assertThat(response.taxRuleVersion()).isNull();
    assertThat(response.taxProvenance()).isNull();
    assertThat(response.serviceChargeBp()).isZero();
    // Safe default (mirrors TaxChargeService.resolve) when no PB1 rule is seeded.
    assertThat(response.serviceChargeInTaxBase()).isTrue();
    assertThat(response.serviceChargeRuleVersion()).isNull();
    assertThat(response.serviceChargeProvenance()).isNull();
  }

  @Test
  void tenantBNeverSeesTenantAsSeededRules() throws Exception {
    // Baseline: tenant A does resolve its seeded rule (sanity, mirrors the first test).
    EffectiveRulesResponse forA =
        TenantContext.callAs(TENANT_A, ACTOR_A, taxChargeService::resolveEffectiveRules);
    assertThat(forA.taxBp()).isEqualTo(1000L);

    // Tenant B, under the SAME running application, resolves nothing of A's — RLS isolation, not
    // merely "a fresh tenant happens to have no rows".
    EffectiveRulesResponse forB =
        TenantContext.callAs(TENANT_B, ACTOR_B, taxChargeService::resolveEffectiveRules);
    assertThat(forB.taxBp()).isZero();
    assertThat(forB.taxRuleVersion()).isNull();
    assertThat(forB.serviceChargeBp()).isZero();
    assertThat(forB.serviceChargeRuleVersion()).isNull();
  }
}
