package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.projection.StatutoryRuleDetailView;
import id.co.nativeapp.employee.payroll.projection.StatutoryRuleSummaryView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link StatutoryRule}. Derived queries only; RLS-scoped (rule 5). The run
 * resolves the SINGLE effective rule per rule_key for the period's as-of date (the freeze step).
 */
public interface StatutoryRuleRepository extends JpaRepository<StatutoryRule, UUID> {

  /** Every active statutory rule effective on the as-of date (one per rule_key is expected). */
  List<StatutoryRule> findByActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** A single rule_key's effective row on the as-of date. */
  Optional<StatutoryRule>
      findByRuleKeyAndActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
          String ruleKey, LocalDate from, LocalDate to);

  /**
   * The currently-open (never-superseded, {@code effective_to = 9999-12-31}) active row for a
   * rule_key — the PATCH override's "current rule" and the detail GET's target. At most one is
   * expected per rule_key by construction (the seed/override writers always close-or-deactivate the
   * prior open row in the same transaction as inserting the new one).
   */
  Optional<StatutoryRule> findByRuleKeyAndActiveTrueAndEffectiveTo(
      String ruleKey, LocalDate effectiveTo);

  /**
   * Whether a specific (rule_key, rule_version) has already been seeded (idempotent-reseed check).
   */
  Optional<StatutoryRule> findByRuleKeyAndRuleVersion(String ruleKey, String ruleVersion);

  /**
   * Every ACTIVE row for a rule_key still open at-or-after {@code effectiveFrom} — the seed/PATCH
   * overlap set a superseding row must close (mirrors how {@code PayrollRunWriter.
   * resolveStatutoryRules} detects an ambiguous overlap; this is the write-side counterpart that
   * prevents one).
   */
  List<StatutoryRule> findByRuleKeyAndActiveTrueAndEffectiveToGreaterThanEqual(
      String ruleKey, LocalDate effectiveFrom);

  /**
   * Any one currently-active rule row — used ONLY to infer the tenant's already-established payroll
   * currency before an official-dataset seed (no currency is accepted on that request).
   */
  Optional<StatutoryRule> findFirstByActiveTrue();

  /** The distinct provenances present (scalar strings — the setup-status read). */
  @Query(value = "SELECT DISTINCT sr.provenance FROM statutory_rule sr", nativeQuery = true)
  List<String> findDistinctProvenances();

  /** The newest illustrative rule-version label, or null when none exists (scalar). */
  @Query(
      value =
          "SELECT MAX(sr.rule_version) FROM statutory_rule sr"
              + " WHERE sr.provenance = 'ILLUSTRATIVE_PLACEHOLDER'",
      nativeQuery = true)
  String findLatestIllustrativeVersion();

  /**
   * The console's rules table ({@code GET /api/v1/payroll-setup/rules}) — every row, no {@code
   * params_json}/{@code currency} (a narrow projection read, CODE-STRUCTURE §3.3), newest
   * effective_from first within each rule_key.
   */
  @Query(
      value =
          """
          SELECT rule_key        AS rule_key,
                 rule_version    AS rule_version,
                 calc_type       AS calc_type,
                 provenance      AS provenance,
                 effective_from  AS effective_from,
                 effective_to    AS effective_to,
                 source_note     AS source_note,
                 active          AS active
            FROM statutory_rule
           ORDER BY rule_key, effective_from DESC
          """,
      nativeQuery = true)
  List<StatutoryRuleSummaryView> findSummaryViews();

  /**
   * The full detail (including {@code params_json}) of a rule_key's currently-open row — {@code GET
   * /api/v1/payroll-setup/rules/{ruleKey}}, the override dialog's source of truth.
   */
  @Query(
      value =
          """
          SELECT rule_key        AS rule_key,
                 rule_version    AS rule_version,
                 calc_type       AS calc_type,
                 params_json     AS params_json,
                 currency        AS currency,
                 provenance      AS provenance,
                 source_note     AS source_note,
                 effective_from  AS effective_from,
                 effective_to    AS effective_to
            FROM statutory_rule
           WHERE rule_key = :ruleKey AND effective_to = :openEnded AND active = true
          """,
      nativeQuery = true)
  Optional<StatutoryRuleDetailView> findOpenDetailByRuleKey(
      @Param("ruleKey") String ruleKey, @Param("openEnded") LocalDate openEnded);
}
