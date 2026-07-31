package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.pricing.domain.TaxChargeRule;
import id.co.nativeapp.barbershop.pricing.dto.EffectiveRulesResponse;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for {@link TaxChargeService#resolveEffectiveRules} — the {@code GET
 * /api/v1/barbershop/pricing/effective-rules} preview endpoint (Phase 5 offline mode, ADR 0028)
 * backing method, against a real {@code tax_charge_rule} table (Testcontainers PostgreSQL). Ported
 * verbatim from carwash-service (ADR 0024). Covers a seeded-rule resolution (both rule families,
 * with provenance), the full no-rule fall-through (zeros/nulls INCLUDING {@code currency}), and RLS
 * tenant isolation — mirrors {@link TaxChargeRuleResolutionTest}'s seeding strategy (direct
 * admin/BYPASSRLS inserts; {@code tax_charge_rule} is deliberately NOT truncated by {@link
 * PostgresRlsTestBase#resetTables()}, so every scenario here uses its OWN dedicated tenant id,
 * distinct from that class's {@code 77777777-...} range).
 *
 * <p>{@link TaxChargeService#resolveEffectiveRules} is itself {@code @Transactional(readOnly =
 * true)} (unlike {@link TaxChargeService#resolve}, which relies on the caller's transaction), so it
 * is invoked directly through {@link TenantContext#callAs} with no test-only transactional wrapper
 * needed.
 */
@SpringBootTest
class EffectiveRulesResolutionTest extends PostgresRlsTestBase {

  private static final String CURRENCY = "IDR";

  @Autowired private TaxChargeService taxChargeService;

  private EffectiveRulesResponse resolveAs(String tenant) throws Exception {
    return TenantContext.callAs(
        tenant, "pricing-effective-rules-test", taxChargeService::resolveEffectiveRules);
  }

  // -----------------------------------------------------------------------
  // 1. A seeded rule (both families) resolves with full provenance/version detail.
  // -----------------------------------------------------------------------

  @Test
  void bothSeededRuleFamiliesResolveWithProvenanceAndCurrency() throws Exception {
    String tenant = "66666666-6666-6666-6666-660000000001";
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "TEST-VAT-1",
        1100L,
        true,
        "ILLUSTRATIVE_PLACEHOLDER",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);
    seedRule(
        tenant,
        TaxChargeRule.KEY_SERVICE_CHARGE,
        "TEST-SC-1",
        500L,
        true,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    EffectiveRulesResponse response = resolveAs(tenant);

    assertThat(response.currency()).isEqualTo(CURRENCY);
    assertThat(response.asOf()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    assertThat(response.taxBp()).isEqualTo(1100L);
    assertThat(response.taxRuleVersion()).isEqualTo("TEST-VAT-1");
    assertThat(response.taxProvenance()).isEqualTo("ILLUSTRATIVE_PLACEHOLDER");
    assertThat(response.serviceChargeBp()).isEqualTo(500L);
    assertThat(response.serviceChargeInTaxBase()).isTrue();
    assertThat(response.serviceChargeRuleVersion()).isEqualTo("TEST-SC-1");
    assertThat(response.serviceChargeProvenance()).isEqualTo("OFFICIAL");
  }

  // -----------------------------------------------------------------------
  // 2. No rule seeded at all -> full zero/null fall-through, including currency.
  // -----------------------------------------------------------------------

  @Test
  void noRuleSeededFallsThroughToZerosAndNullsIncludingCurrency() throws Exception {
    String tenant = "66666666-6666-6666-6666-660000000002";

    EffectiveRulesResponse response = resolveAs(tenant);

    assertThat(response.currency()).isNull();
    assertThat(response.asOf()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    assertThat(response.taxBp()).isZero();
    assertThat(response.taxRuleVersion()).isNull();
    assertThat(response.taxProvenance()).isNull();
    assertThat(response.serviceChargeBp()).isZero();
    // No tax rule seeded -> the safe default (mirrors TaxChargeService.resolve).
    assertThat(response.serviceChargeInTaxBase()).isTrue();
    assertThat(response.serviceChargeRuleVersion()).isNull();
    assertThat(response.serviceChargeProvenance()).isNull();
  }

  // -----------------------------------------------------------------------
  // 3. RLS tenant isolation: two tenants seeded with DISTINCT rules never see each other's.
  // -----------------------------------------------------------------------

  @Test
  void tenantIsolationBetweenTwoCompaniesEffectiveRules() throws Exception {
    String tenantA = "66666666-6666-6666-6666-660000000003";
    String tenantB = "66666666-6666-6666-6666-660000000004";
    seedRule(
        tenantA,
        TaxChargeRule.KEY_VAT,
        "ISO-A-1",
        500L,
        true,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);
    seedRule(
        tenantB,
        TaxChargeRule.KEY_VAT,
        "ISO-B-1",
        1900L,
        true,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    EffectiveRulesResponse asA = resolveAs(tenantA);
    EffectiveRulesResponse asB = resolveAs(tenantB);

    assertThat(asA.taxBp()).isEqualTo(500L);
    assertThat(asA.taxRuleVersion()).isEqualTo("ISO-A-1");
    assertThat(asB.taxBp()).isEqualTo(1900L);
    assertThat(asB.taxRuleVersion()).isEqualTo("ISO-B-1");
  }

  // -----------------------------------------------------------------------
  // Admin seeding helper — bypasses RLS (mirrors TaxChargeRuleResolutionTest's).
  // -----------------------------------------------------------------------

  @SuppressWarnings("checkstyle:ParameterNumber")
  private void seedRule(
      String companyId,
      String ruleKey,
      String ruleVersion,
      long rateBp,
      boolean serviceChargeInTaxBase,
      String provenance,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                """
                INSERT INTO tax_charge_rule (
                    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
                    provenance, source_note, currency,
                    effective_from, effective_to, active,
                    created_at, created_by, updated_at, updated_by, version, company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE,
                          now(), 'pricing-test-seed', now(), 'pricing-test-seed', 0, ?)
                """)) {
      ps.setObject(1, java.util.UUID.randomUUID());
      ps.setString(2, ruleKey);
      ps.setString(3, ruleVersion);
      ps.setLong(4, rateBp);
      ps.setBoolean(5, serviceChargeInTaxBase);
      ps.setString(6, provenance);
      ps.setString(7, "Seeded by EffectiveRulesResolutionTest — not a real rule.");
      ps.setString(8, CURRENCY);
      ps.setObject(9, effectiveFrom);
      ps.setObject(10, effectiveTo);
      ps.setString(11, companyId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("seedRule failed", e);
    }
  }
}
