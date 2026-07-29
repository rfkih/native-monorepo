package id.co.nativeapp.finance.bank.repository;

import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.projection.StatementLineView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link BankStatementLine} aggregate. Inherited {@code findById}/{@code save}
 * are the write path (whole aggregate — the import + reconcile writers need to mutate it); the
 * native-query {@link StatementLineView} projections and the scalar aggregate queries below are the
 * read path. All access is RLS-scoped to the bound tenant (no manual {@code WHERE company_id} — the
 * {@code bank_statement_line} table is under FORCE RLS, V29).
 */
public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {

  /**
   * The statement lines for one bank account, with an optional {@code status} filter (a null
   * disables it), newest line-date first. Bounded at 500 (mirroring AR/AP's LIMIT convention).
   */
  @Query(
      value =
          "SELECT id, bank_account_id AS bank_account_id, line_date AS line_date,"
              + " amount_minor AS amount_minor, currency, description, reference, status,"
              + " reconciled_category AS reconciled_category,"
              + " journal_entry_id AS journal_entry_id"
              + " FROM bank_statement_line"
              + " WHERE bank_account_id = :bankAccountId"
              + " AND (:status IS NULL OR status = :status)"
              + " ORDER BY line_date DESC, created_at DESC LIMIT 500",
      nativeQuery = true)
  List<StatementLineView> findByBankAccount(UUID bankAccountId, String status);

  /** One statement line by id in the bound tenant (RLS-scoped) — the line detail read. */
  @Query(
      value =
          "SELECT id, bank_account_id AS bank_account_id, line_date AS line_date,"
              + " amount_minor AS amount_minor, currency, description, reference, status,"
              + " reconciled_category AS reconciled_category,"
              + " journal_entry_id AS journal_entry_id"
              + " FROM bank_statement_line WHERE id = :id",
      nativeQuery = true)
  Optional<StatementLineView> findViewById(UUID id);

  /**
   * Σ {@code amount_minor} of the RECONCILED lines for one bank account — the sub-ledger's bank
   * balance (the reconciliation report). A scalar aggregate, not a projection of entity columns
   * (CLAUDE.md §"count/exists return scalars").
   */
  @Query(
      value =
          "SELECT COALESCE(SUM(amount_minor), 0) FROM bank_statement_line"
              + " WHERE bank_account_id = :bankAccountId AND status = 'RECONCILED'",
      nativeQuery = true)
  long sumReconciledAmountMinor(UUID bankAccountId);

  /** Count of UNRECONCILED lines for one bank account — the reconciliation report's headline. */
  @Query(
      value =
          "SELECT COUNT(*) FROM bank_statement_line"
              + " WHERE bank_account_id = :bankAccountId AND status = 'UNRECONCILED'",
      nativeQuery = true)
  long countUnreconciled(UUID bankAccountId);
}
