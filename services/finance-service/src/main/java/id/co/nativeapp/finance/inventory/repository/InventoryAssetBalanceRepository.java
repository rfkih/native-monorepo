package id.co.nativeapp.finance.inventory.repository;

import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.inventory.projection.InventoryAssetBalanceView;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * A narrow, READ-ONLY data port onto {@code journal_line} for the D4/D5 status read's {@code 1100
 * INVENTORY} balance (ADR 0067 §5). Deliberately extends the bare Spring Data {@link Repository}
 * marker (NOT {@code JpaRepository}) — the {@code inventory} feature has no business writing/
 * deleting {@link JournalLine} rows, which belong to the {@code gl} feature's own {@code
 * JournalLineRepository}; this port exposes only the one aggregate query it needs.
 *
 * <p>No manual {@code WHERE company_id} — RLS applies the tenant scope (rule 5). The query has no
 * {@code GROUP BY} / {@code WHERE} row-count dependency, so it always returns exactly one row (the
 * {@code COALESCE}d sums default to zero, {@code MAX(currency)} to {@code NULL}, when the tenant
 * has never posted to the account) — no {@code Optional} needed.
 */
public interface InventoryAssetBalanceRepository extends Repository<JournalLine, java.util.UUID> {

  /**
   * {@code Σdebit_minor}, {@code Σcredit_minor}, and the (single, by construction — {@link
   * id.co.nativeapp.finance.gl.domain.JournalEntry#balanced} enforces one currency per entry, and a
   * company's base currency is immutable once set) currency posted to {@code accountCode} for the
   * bound tenant. Never {@code SELECT *} — three named columns via a projection.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(debit_minor), 0)  AS total_debit_minor,
                 COALESCE(SUM(credit_minor), 0) AS total_credit_minor,
                 MAX(currency)                  AS currency
            FROM journal_line
           WHERE account_code = :accountCode
          """,
      nativeQuery = true)
  InventoryAssetBalanceView balanceFor(@Param("accountCode") String accountCode);
}
