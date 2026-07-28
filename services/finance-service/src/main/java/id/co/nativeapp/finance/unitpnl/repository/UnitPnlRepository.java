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
   * The unit itself plus its child outlets, each LEFT-JOINed to two PRE-AGGREGATED legs:
   *
   * <ul>
   *   <li><strong>Revenue = the NET accumulator ({@code outlet_revenue}).</strong> The dimensional
   *       ledger's {@code amount_minor} for a sale is the GRAND TOTAL (customer-pays, incl. service
   *       charge + tax), while every P&amp;L surface reports NET revenue (subtotal − discount) —
   *       summing the ledger would overstate revenue by Σ(serviceCharge + tax) and never reconcile
   *       with {@code GET /api/v1/pnl} / {@code /pnl/outlets}. {@code outlet_revenue} is the same
   *       net source {@code /pnl/outlets} serves, maintained by the sale + void/refund consumers
   *       (reversal netting happens in the accumulator itself).
   *   <li><strong>Expense = the dimensional ledger.</strong> Expense/labor postings carry the
   *       actual amount; the signed SUM nets labor-supersession REVERSAL rows. {@code
   *       uses_illustrative_rules} is OR'd over ALL of the node's postings (revenue legs included —
   *       the flag is display metadata). Only {@code REVENUE}/{@code EXPENSE} posting types exist
   *       today; a future type would need its own leg here.
   * </ul>
   *
   * <p>Each leg is pre-aggregated per {@code business_id} in its own subquery so the two joins
   * cannot fan out against each other (no cross-product double counting). Other properties:
   *
   * <ul>
   *   <li>The type comparison is CASE-INSENSITIVE: {@code org_unit_ref.type} stores whatever the
   *       events carry — {@code OrgUnitCreatedSchema} emits the enum NAME ({@code "OUTLET"},
   *       uppercase), though older prose (V22 comments, one contract-test fixture) suggests
   *       lowercase. {@code upper(ou.type)} makes the rollup immune to that history.
   *   <li>The tree is one level below a business unit (ADR 0012), so no recursive CTE.
   *   <li>The LEFT JOINs keep a known unit with zero postings in the result — the existence check
   *       and the rollup are one query.
   *   <li>The all-zeros labor-suspense sentinel {@code business_id} never equals an {@code
   *       org_unit_ref.org_unit_id}, so it is excluded structurally.
   *   <li>No {@code WHERE company_id} — RLS scopes all three tables to the bound tenant (rule 5),
   *       which also makes a foreign unit id indistinguishable from an unknown one.
   * </ul>
   */
  @Query(
      value =
          """
          SELECT ou.org_unit_id AS org_unit_id,
                 ou.name        AS name,
                 ou.type        AS type,
                 ou.active      AS active,
                 COALESCE(rev.revenue_minor, 0) AS revenue_minor,
                 COALESCE(exp.expense_minor, 0) AS expense_minor,
                 COALESCE(exp.uses_illustrative_rules, false) AS uses_illustrative_rules,
                 rev.currency               AS revenue_currency,
                 exp.currency               AS expense_currency,
                 COALESCE(rev.currency_count, 0) AS revenue_currency_count,
                 COALESCE(exp.currency_count, 0) AS expense_currency_count
            FROM org_unit_ref ou
            LEFT JOIN (
                SELECT orv.business_id,
                       SUM(orv.revenue_minor)       AS revenue_minor,
                       MIN(orv.currency)            AS currency,
                       COUNT(DISTINCT orv.currency) AS currency_count
                  FROM outlet_revenue orv
                 WHERE orv.period = :period
                 GROUP BY orv.business_id
            ) rev ON rev.business_id = ou.org_unit_id
            LEFT JOIN (
                SELECT lp.business_id,
                       COALESCE(SUM(lp.amount_minor)
                           FILTER (WHERE lp.posting_type = 'EXPENSE'), 0) AS expense_minor,
                       bool_or(lp.uses_illustrative_rules)                AS uses_illustrative_rules,
                       MIN(lp.currency)
                           FILTER (WHERE lp.posting_type = 'EXPENSE')     AS currency,
                       COUNT(DISTINCT lp.currency)
                           FILTER (WHERE lp.posting_type = 'EXPENSE')     AS currency_count
                  FROM ledger_posting lp
                 WHERE lp.period = :period
                 GROUP BY lp.business_id
            ) exp ON exp.business_id = ou.org_unit_id
           WHERE ou.org_unit_id = :orgUnitId
              OR (ou.parent_id = :orgUnitId AND upper(ou.type) = 'OUTLET')
           ORDER BY revenue_minor DESC, ou.name
          """,
      nativeQuery = true)
  List<UnitPnlRowView> rollupForPeriod(
      @Param("orgUnitId") UUID orgUnitId, @Param("period") String period);
}
