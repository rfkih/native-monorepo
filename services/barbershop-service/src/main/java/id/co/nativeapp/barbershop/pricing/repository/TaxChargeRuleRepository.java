package id.co.nativeapp.barbershop.pricing.repository;

import id.co.nativeapp.barbershop.pricing.domain.TaxChargeRule;
import id.co.nativeapp.barbershop.pricing.projection.TaxChargeRuleView;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link TaxChargeRule} (ported from carwash-service's {@code pricing}
 * feature). All queries are native (rule 10 / CODE-STRUCTURE.md §3.3). The RLS policy on {@code
 * tax_charge_rule} scopes every query to the bound tenant automatically — no manual {@code WHERE
 * company_id} is required.
 *
 * <p>The effective-rule resolver follows the same pattern as {@code PostingTemplateResolver} /
 * {@code GlAccountResolver} in finance: highest-version active row whose effective window covers
 * the supplied date, within the tenant scope.
 */
public interface TaxChargeRuleRepository extends JpaRepository<TaxChargeRule, java.util.UUID> {

  /**
   * Resolves the single effective {@link TaxChargeRuleView} for a given {@code ruleKey} at a
   * specific date — highest {@code version} (implicit from the row's {@code rule_version} ordering)
   * active row whose {@code effective_from ≤ asOf ≤ effective_to}. RLS-scoped to the bound tenant.
   *
   * <p>Uses {@code ORDER BY rule_version DESC LIMIT 1} as a proxy for "latest" (the seeding
   * convention is that a newer rule carries a lexicographically-later version label, e.g. {@code
   * "OFFICIAL-2027.1"} sorts after {@code "ILLUSTRATIVE-2026.1"}). An accountant seeds a
   * higher-version row to supersede the illustrative placeholder.
   *
   * @param ruleKey the rule family key ({@code VAT_BARBERSHOP} or {@code SERVICE_CHARGE})
   * @param asOf the date the ticket occurred on (used to filter the effective window)
   * @return the highest-version active rule effective at {@code asOf}, or empty if none is seeded
   */
  @Query(
      value =
          """
          SELECT rule_key      AS ruleKey,
                 rule_version  AS ruleVersion,
                 rate_bp       AS rateBp,
                 service_charge_in_tax_base AS serviceChargeInTaxBase,
                 provenance    AS provenance,
                 currency      AS currency
            FROM tax_charge_rule
           WHERE rule_key       = :ruleKey
             AND active         = TRUE
             AND effective_from <= :asOf
             AND effective_to   >= :asOf
           ORDER BY rule_version DESC
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<TaxChargeRuleView> findEffective(
      @Param("ruleKey") String ruleKey, @Param("asOf") LocalDate asOf);
}
