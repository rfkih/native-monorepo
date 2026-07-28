package id.co.nativeapp.finance.unitpnl.repository;

import id.co.nativeapp.finance.orgref.domain.OrgUnitRef;
import id.co.nativeapp.finance.unitpnl.projection.UnitPnlRowView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read port for the per-org-unit P&amp;L rollup. Anchored on {@link OrgUnitRef} (the {@code
 * orgref.domain} read model — Repository→Domain is the allowed layer direction) purely to satisfy
 * Spring Data; only the native rollup query below is used.
 */
public interface UnitPnlRepository extends JpaRepository<OrgUnitRef, UUID> {

  /**
   * The unit itself plus its child outlets, each LEFT-JOINed to its {@code ledger_posting} rows for
   * the period with signed sums (REVERSAL rows are negative, so {@code SUM} nets them against
   * PRIMARY — same mechanism as the GL trial balance). Key properties:
   *
   * <ul>
   *   <li>The type comparison is CASE-INSENSITIVE: {@code org_unit_ref.type} stores whatever the
   *       events carry — {@code OrgUnitCreatedSchema} emits the enum NAME ({@code "OUTLET"},
   *       uppercase), though older prose (V22 comments, one contract-test fixture) suggests
   *       lowercase. {@code upper(ou.type)} makes the rollup immune to that history.
   *   <li>The tree is one level below a business unit (ADR 0012), so no recursive CTE.
   *   <li>The LEFT JOIN keeps a known unit with zero postings in the result — the existence check
   *       and the rollup are one query.
   *   <li>The all-zeros labor-suspense sentinel {@code business_id} never equals an {@code
   *       org_unit_ref.org_unit_id}, so it is excluded structurally.
   *   <li>No {@code WHERE company_id} — RLS scopes both tables to the bound tenant (rule 5), which
   *       also makes a foreign unit id indistinguishable from an unknown one.
   * </ul>
   */
  @Query(
      value =
          """
          SELECT ou.org_unit_id AS org_unit_id,
                 ou.name        AS name,
                 ou.type        AS type,
                 ou.active      AS active,
                 COALESCE(SUM(lp.amount_minor)
                     FILTER (WHERE lp.posting_type = 'REVENUE'), 0) AS revenue_minor,
                 COALESCE(SUM(lp.amount_minor)
                     FILTER (WHERE lp.posting_type = 'EXPENSE'), 0) AS expense_minor,
                 COALESCE(bool_or(lp.uses_illustrative_rules), false) AS uses_illustrative_rules,
                 MIN(lp.currency)            AS currency,
                 COUNT(DISTINCT lp.currency) AS currency_count
            FROM org_unit_ref ou
            LEFT JOIN ledger_posting lp
                   ON lp.business_id = ou.org_unit_id
                  AND lp.period = :period
           WHERE ou.org_unit_id = :orgUnitId
              OR (ou.parent_id = :orgUnitId AND upper(ou.type) = 'OUTLET')
           GROUP BY ou.org_unit_id, ou.name, ou.type, ou.active
           ORDER BY revenue_minor DESC, ou.name
          """,
      nativeQuery = true)
  List<UnitPnlRowView> rollupForPeriod(
      @Param("orgUnitId") UUID orgUnitId, @Param("period") String period);
}
