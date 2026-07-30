package id.co.nativeapp.loyalty.earnrule.repository;

import id.co.nativeapp.loyalty.earnrule.domain.EarnRule;
import id.co.nativeapp.loyalty.earnrule.projection.EarnRuleView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link EarnRule}. All queries are native (rule 10 / CODE-STRUCTURE.md
 * §3.3). The RLS policy on {@code earn_rule} scopes every query to the bound tenant automatically —
 * no manual {@code WHERE company_id} is required.
 */
public interface EarnRuleRepository extends JpaRepository<EarnRule, UUID> {

  /**
   * Resolves the single effective earn rule at a given date — highest-version active row whose
   * effective window covers {@code asOf}, within the tenant scope. Empty when the company has never
   * configured an earn rule (zero-earn until configured, by design — no seed row).
   */
  @Query(
      value =
          """
          SELECT id                    AS id,
                 rule_version          AS ruleVersion,
                 points_per_minor_bp   AS pointsPerMinorBp,
                 min_sale_minor        AS minSaleMinor,
                 provenance            AS provenance,
                 source_note           AS sourceNote,
                 effective_from        AS effectiveFrom,
                 effective_to          AS effectiveTo,
                 active                AS active
            FROM earn_rule
           WHERE active         = TRUE
             AND effective_from <= :asOf
             AND effective_to   >= :asOf
           ORDER BY rule_version DESC
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<EarnRuleView> findEffective(@Param("asOf") LocalDate asOf);

  /** Every rule for the admin listing endpoint, optionally restricted to active-only. */
  @Query(
      value =
          """
          SELECT id                    AS id,
                 rule_version          AS ruleVersion,
                 points_per_minor_bp   AS pointsPerMinorBp,
                 min_sale_minor        AS minSaleMinor,
                 provenance            AS provenance,
                 source_note           AS sourceNote,
                 effective_from        AS effectiveFrom,
                 effective_to          AS effectiveTo,
                 active                AS active
            FROM earn_rule
           WHERE (:activeOnly = FALSE OR active = TRUE)
           ORDER BY rule_version DESC
          """,
      nativeQuery = true)
  List<EarnRuleView> findViews(@Param("activeOnly") boolean activeOnly);
}
