package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.pricing.domain.TaxChargeRule;
import id.co.nativeapp.carwash.pricing.service.TaxChargeService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link TaxChargeService#resolve} against a real {@code tax_charge_rule}
 * table (Testcontainers PostgreSQL), covering the resolution behaviour restaurant-service exercises
 * only implicitly via its V5-seeded illustrative row: effective-dating, the illustrative-flag
 * propagation + version tie-break, the no-{@code SERVICE_CHARGE}-row fall-through to zero, and that
 * the resolver is keyed on {@link TaxChargeRule#KEY_VAT} ({@code VAT_CARWASH}) — NOT restaurant's
 * {@code PB1_RESTAURANT} key.
 *
 * <p><strong>Seeding strategy.</strong> There is no application-level writer for {@code
 * tax_charge_rule} (restaurant-service has none either — rows arrive only via Flyway seed data or a
 * future admin endpoint), so each scenario seeds its rows directly over the admin/BYPASSRLS
 * connection, mirroring exactly how the V5/V6 migrations themselves seed the illustrative {@code
 * VAT_CARWASH} row. Every scenario uses its OWN dedicated tenant id: {@code tax_charge_rule} is
 * deliberately NOT truncated by {@link PostgresRlsTestBase#resetTables()} (so the V5 migration seed
 * survives across the whole suite — see that method's javadoc), so tests must not collide on a
 * shared tenant/rule_version.
 *
 * <p><strong>Resolving inside a real transaction.</strong> {@link TaxChargeService#resolve} is not
 * {@code @Transactional} itself — production always calls it from inside a checkout writer's own
 * {@code @Transactional} unit (see the service's javadoc). This test mirrors that with a small
 * test-only {@code @Transactional} wrapper bean, exactly the pattern {@code OutletAccessGuardTest}
 * and {@code RecordWashAtomicityTest} already use in this module, so the auto-RLS aspect sets the
 * tenant GUC the repository read relies on (rule 5).
 */
@SpringBootTest
class TaxChargeRuleResolutionTest extends PostgresRlsTestBase {

  private static final String CURRENCY = "IDR";

  @Autowired private ResolverInvoker resolverInvoker;

  /** Test-only {@code @Transactional} wrapper — see the class javadoc. */
  @TestConfiguration
  static class ResolverInvokerConfig {
    @Bean
    ResolverInvoker resolverInvoker(TaxChargeService taxChargeService) {
      return new ResolverInvoker(taxChargeService);
    }
  }

  static class ResolverInvoker {
    private final TaxChargeService taxChargeService;

    ResolverInvoker(TaxChargeService taxChargeService) {
      this.taxChargeService = taxChargeService;
    }

    // public: a CGLIB transaction proxy must be able to override this method.
    @Transactional
    public PriceBreakdown resolve(Money subtotal, Instant occurredAt) {
      return taxChargeService.resolve(subtotal, 0L, null, occurredAt);
    }
  }

  private PriceBreakdown resolveAs(String tenant, Money subtotal, Instant occurredAt)
      throws Exception {
    return TenantContext.callAs(
        tenant, "pricing-test-actor", () -> resolverInvoker.resolve(subtotal, occurredAt));
  }

  // -----------------------------------------------------------------------
  // 1. Effective-dating: the resolver picks the rule version covering occurredAt
  // -----------------------------------------------------------------------

  @Test
  void resolutionPicksTheRuleVersionEffectiveAtOccurredAt() throws Exception {
    String tenant = "77777777-7777-7777-7777-770000000001";
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "TEST-A",
        1000L, // 10%
        false,
        "OFFICIAL",
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2025, 12, 31));
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "TEST-B",
        2000L, // 20%
        false,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    Money subtotal = Money.ofMinor(100_000L, CURRENCY);

    PriceBreakdown in2025 = resolveAs(tenant, subtotal, Instant.parse("2025-06-15T00:00:00Z"));
    assertThat(in2025.taxRuleVersion()).isEqualTo("TEST-A");
    assertThat(in2025.tax().amountMinor()).isEqualTo(10_000L);

    PriceBreakdown in2026 = resolveAs(tenant, subtotal, Instant.parse("2026-06-15T00:00:00Z"));
    assertThat(in2026.taxRuleVersion()).isEqualTo("TEST-B");
    assertThat(in2026.tax().amountMinor()).isEqualTo(20_000L);
  }

  // -----------------------------------------------------------------------
  // 2. Illustrative-flag propagation + version tie-break (OFFICIAL supersedes ILLUSTRATIVE)
  // -----------------------------------------------------------------------

  @Test
  void illustrativeRuleSetsTheFlagAndAHigherOfficialVersionSupersedesIt() throws Exception {
    String tenant = "77777777-7777-7777-7777-770000000002";
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "ILLUSTRATIVE-2026.1",
        1100L, // 11%
        false,
        "ILLUSTRATIVE_PLACEHOLDER",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    Money subtotal = Money.ofMinor(100_000L, CURRENCY);
    Instant occurredAt = Instant.parse("2026-06-01T00:00:00Z");

    PriceBreakdown illustrative = resolveAs(tenant, subtotal, occurredAt);
    assertThat(illustrative.usesIllustrativeRules()).isTrue();
    assertThat(illustrative.taxRuleVersion()).isEqualTo("ILLUSTRATIVE-2026.1");
    assertThat(illustrative.tax().amountMinor()).isEqualTo(11_000L);

    // An accountant seeds a higher-version OFFICIAL row over the same window — the resolver's
    // "ORDER BY rule_version DESC" must prefer it (OFFICIAL-2027.1 sorts after ILLUSTRATIVE-2026.1).
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "OFFICIAL-2027.1",
        500L, // 5%
        false,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    PriceBreakdown official = resolveAs(tenant, subtotal, occurredAt);
    assertThat(official.usesIllustrativeRules()).isFalse();
    assertThat(official.taxRuleVersion()).isEqualTo("OFFICIAL-2027.1");
    assertThat(official.tax().amountMinor()).isEqualTo(5_000L);
  }

  // -----------------------------------------------------------------------
  // 3. No SERVICE_CHARGE row seeded → service charge falls through to zero
  // -----------------------------------------------------------------------

  @Test
  void noServiceChargeRuleFallsThroughToZero() throws Exception {
    String tenant = "77777777-7777-7777-7777-770000000003";
    // Only VAT_CARWASH is seeded — no SERVICE_CHARGE row for this tenant, mirroring the carwash V5
    // baseline seed (see that migration's note 2).
    seedRule(
        tenant,
        TaxChargeRule.KEY_VAT,
        "TEST-NOSC",
        1000L, // 10%
        true,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    Money subtotal = Money.ofMinor(50_000L, CURRENCY);
    PriceBreakdown breakdown =
        resolveAs(tenant, subtotal, Instant.parse("2026-06-01T00:00:00Z"));

    assertThat(breakdown.serviceCharge().isZero()).isTrue();
    // taxBase = taxableBase + SC(0) = 50,000; tax = 10% of 50,000 = 5,000.
    assertThat(breakdown.tax().amountMinor()).isEqualTo(5_000L);
    assertThat(breakdown.grandTotal().amountMinor()).isEqualTo(55_000L);
  }

  // -----------------------------------------------------------------------
  // 4. Resolution is keyed on VAT_CARWASH, not restaurant's PB1_RESTAURANT
  // -----------------------------------------------------------------------

  @Test
  void keyConstantIsVatCarwashNotRestaurantsPb1Key() {
    assertThat(TaxChargeRule.KEY_VAT).isEqualTo("VAT_CARWASH");
  }

  @Test
  void aRowSeededUnderRestaurantsOldPb1KeyIsNeverResolved() throws Exception {
    String tenant = "77777777-7777-7777-7777-770000000004";
    // Simulate a stray/leftover row under the OLD restaurant key — the carwash resolver must NOT
    // pick it up; it queries TaxChargeRule.KEY_VAT ("VAT_CARWASH") only.
    seedRule(
        tenant,
        "PB1_RESTAURANT",
        "TEST-WRONG-KEY",
        999L,
        true,
        "OFFICIAL",
        LocalDate.of(2026, 1, 1),
        TaxChargeRule.OPEN_ENDED);

    Money subtotal = Money.ofMinor(10_000L, CURRENCY);
    PriceBreakdown breakdown =
        resolveAs(tenant, subtotal, Instant.parse("2026-06-01T00:00:00Z"));

    assertThat(breakdown.tax().isZero()).isTrue();
    assertThat(breakdown.taxRuleVersion()).isNull();
    assertThat(breakdown.grandTotal()).isEqualTo(subtotal);
  }

  // -----------------------------------------------------------------------
  // 5. No rule seeded at all → full zero fall-through (mirrors restaurant's PriceBreakdownTest)
  // -----------------------------------------------------------------------

  @Test
  void noRuleSeededAtAllFallsThroughToZeroEverywhere() throws Exception {
    String tenant = "77777777-7777-7777-7777-770000000005";
    Money subtotal = Money.ofMinor(20_000L, CURRENCY);

    PriceBreakdown breakdown =
        resolveAs(tenant, subtotal, Instant.parse("2026-06-01T00:00:00Z"));

    assertThat(breakdown.tax().isZero()).isTrue();
    assertThat(breakdown.serviceCharge().isZero()).isTrue();
    assertThat(breakdown.grandTotal()).isEqualTo(subtotal);
    assertThat(breakdown.taxRuleVersion()).isNull();
    assertThat(breakdown.usesIllustrativeRules()).isFalse();
  }

  // -----------------------------------------------------------------------
  // Admin seeding helper (bypasses RLS — a BYPASSRLS superuser connection, same pattern the V5/V6
  // migrations themselves use, and the same admin-connection pattern this test module already uses
  // for post-condition assertions elsewhere).
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
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, ruleKey);
      ps.setString(3, ruleVersion);
      ps.setLong(4, rateBp);
      ps.setBoolean(5, serviceChargeInTaxBase);
      ps.setString(6, provenance);
      ps.setString(7, "Seeded by TaxChargeRuleResolutionTest — not a real rule.");
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
