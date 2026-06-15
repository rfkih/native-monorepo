package id.co.nativeapp.finance.pnl.service;

import id.co.nativeapp.money.Money;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maintains the {@code consolidated_pnl} read model — the P&amp;L the dashboard reads — by
 * accumulating a posting's revenue or expense leg with an ATOMIC {@code INSERT … ON CONFLICT … DO
 * UPDATE} per posting. There is NO read-modify-write window, so concurrent distinct postings for
 * the same {@code (company_id, period, currency)} never lose an update (§3.2), exactly as the
 * existing consolidated-revenue accumulator does.
 *
 * <p>It is a plain {@code @Component} with no {@code @Transactional} of its own: it is always
 * invoked from inside a posting {@code @Transactional} unit (the revenue or expense writer), so its
 * upsert runs on that transaction's connection and commits atomically with the ledger posting under
 * the RLS GUC the aspect set — the policy's {@code WITH CHECK} binds {@code company_id} on the
 * insert path.
 *
 * <p>Revenue and expense are accumulated into SEPARATE columns ({@code revenue_minor} / {@code
 * expense_minor}); the net (revenue − expense) is derived on read, so it can never drift from its
 * components. Money stays minor units + currency, never a float (rule 8).
 */
@Component
public class PnlReadModelWriter {

  // Shared upsert skeleton: insert a fresh (company, period, currency) P&L row with one leg seeded
  // and the other zero, or add this posting's minor units onto the matching leg of the existing
  // row. now()/actor stamp Auditable updated_at/updated_by on every accumulate; created_* are set
  // only on the first insert and never overwritten. The leg column name is the only thing that
  // differs between the revenue and expense paths, so it is formatted in (a fixed identifier from
  // our own constants — never user input — so no injection surface).
  //
  // uses_illustrative_rules is STICKY/MONOTONIC: the upsert ORs it in, so once any illustrative-
  // derived labor lands in a (company, period, currency) the row is flagged true and stays true (a
  // later non-illustrative posting cannot clear it) — the consolidated figure is now contaminated
  // by
  // placeholder data and a dashboard must keep badging the period provisional (#23).
  private static final String UPSERT_TEMPLATE =
      """
      INSERT INTO consolidated_pnl
          (id, period, %1$s, currency, uses_illustrative_rules,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, period, currency) DO UPDATE SET
          %1$s                    = consolidated_pnl.%1$s + EXCLUDED.%1$s,
          uses_illustrative_rules = consolidated_pnl.uses_illustrative_rules
                                      OR EXCLUDED.uses_illustrative_rules,
          updated_at              = now(),
          updated_by              = EXCLUDED.updated_by,
          version                 = consolidated_pnl.version + 1
      """;

  private static final String UPSERT_REVENUE_SQL = UPSERT_TEMPLATE.formatted("revenue_minor");
  private static final String UPSERT_EXPENSE_SQL = UPSERT_TEMPLATE.formatted("expense_minor");

  private final JdbcTemplate jdbcTemplate;

  public PnlReadModelWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Accumulates a posting's amount onto the period's REVENUE leg (atomic upsert). Revenue is never
   * illustrative-derived, so the illustrative flag is contributed as {@code false}.
   */
  public void addRevenue(String period, Money amount, String companyId, String actor) {
    accumulate(UPSERT_REVENUE_SQL, period, amount, companyId, actor, false);
  }

  /**
   * Accumulates a posting's amount onto the period's EXPENSE leg (atomic upsert). The {@code
   * ExpenseRecorded} path is never illustrative-derived, so the flag is contributed as {@code
   * false}.
   */
  public void addExpense(String period, Money amount, String companyId, String actor) {
    accumulate(UPSERT_EXPENSE_SQL, period, amount, companyId, actor, false);
  }

  /**
   * Accumulates a labor posting's amount onto the period's EXPENSE leg, carrying the {@code
   * uses_illustrative_rules} flag (#23). A PRIMARY labor posting passes its positive amount; a
   * REVERSAL passes {@code amount.negate()}, moving the expense leg back down — net stays correct
   * and append-only. The flag is OR-ed into the row monotonically (sticky), so an illustrative run
   * contaminates the period's P&amp;L permanently until a clean period-close recompute.
   */
  public void addExpense(
      String period, Money amount, String companyId, String actor, boolean usesIllustrative) {
    accumulate(UPSERT_EXPENSE_SQL, period, amount, companyId, actor, usesIllustrative);
  }

  private void accumulate(
      String sql,
      String period,
      Money amount,
      String companyId,
      String actor,
      boolean usesIllustrative) {
    jdbcTemplate.update(
        sql,
        UUID.randomUUID(),
        period,
        amount.amountMinor(),
        amount.currency().getCurrencyCode(),
        usesIllustrative,
        actor,
        actor,
        companyId);
  }
}
