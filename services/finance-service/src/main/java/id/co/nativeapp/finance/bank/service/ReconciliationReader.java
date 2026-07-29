package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.domain.StatementLineStatus;
import id.co.nativeapp.finance.bank.dto.ReconciliationReportResponse;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.projection.BankAccountView;
import id.co.nativeapp.finance.bank.projection.StatementLineView;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import id.co.nativeapp.finance.bank.repository.BankStatementLineRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the bank reconciliation report for one bank account: the sub-ledger's bank balance (Σ
 * {@code amount_minor} of its RECONCILED lines), the tenant's {@code CASH_CLEARING} (1900) GL
 * balance (cash-in-transit awaiting sweep — company-wide, not per-account), and the count/list of
 * UNRECONCILED lines still awaiting action.
 *
 * <p>The {@code CASH_CLEARING} balance is a plain scalar aggregate over {@code journal_line} (a
 * {@code gl}-owned table), read via {@link JdbcTemplate} rather than a new {@code
 * JournalLineRepository} method — mirroring how AR/AP's writers already query {@code journal_entry}
 * directly for the currency-consistency guard, so this report does not widen the {@code gl}
 * feature's repository surface for one report-only aggregate.
 */
@Service
public class ReconciliationReader {

  /** The one company-wide cash-in-transit clearing account every sale/receipt/payment sweeps. */
  private static final String CASH_CLEARING_ACCOUNT_CODE = "1900";

  private static final String CASH_CLEARING_BALANCE_SQL =
      "SELECT COALESCE(SUM(debit_minor - credit_minor), 0) FROM journal_line WHERE account_code = ?";

  private final BankAccountRepository bankAccountRepository;
  private final BankStatementLineRepository statementLineRepository;
  private final JdbcTemplate jdbcTemplate;

  public ReconciliationReader(
      BankAccountRepository bankAccountRepository,
      BankStatementLineRepository statementLineRepository,
      JdbcTemplate jdbcTemplate) {
    this.bankAccountRepository =
        Objects.requireNonNull(bankAccountRepository, "bankAccountRepository");
    this.statementLineRepository =
        Objects.requireNonNull(statementLineRepository, "statementLineRepository");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * The reconciliation report for {@code bankAccountId}.
   *
   * @throws BankAccountNotFoundException if the bank account is not in the bound tenant
   */
  @Transactional(readOnly = true)
  public ReconciliationReportResponse report(UUID bankAccountId) {
    BankAccountView account =
        bankAccountRepository
            .findViewById(bankAccountId)
            .orElseThrow(() -> new BankAccountNotFoundException(bankAccountId));

    long bankBalanceMinor = statementLineRepository.sumReconciledAmountMinor(bankAccountId);
    long unreconciledCount = statementLineRepository.countUnreconciled(bankAccountId);
    List<StatementLineResponse> unreconciledLines =
        statementLineRepository
            .findByBankAccount(bankAccountId, StatementLineStatus.UNRECONCILED.name())
            .stream()
            .map(ReconciliationReader::toResponse)
            .toList();

    Long cashClearingBalance =
        jdbcTemplate.queryForObject(
            CASH_CLEARING_BALANCE_SQL, Long.class, CASH_CLEARING_ACCOUNT_CODE);

    return new ReconciliationReportResponse(
        bankAccountId,
        account.getCurrency(),
        bankBalanceMinor,
        cashClearingBalance == null ? 0L : cashClearingBalance,
        unreconciledCount,
        unreconciledLines);
  }

  private static StatementLineResponse toResponse(StatementLineView view) {
    return new StatementLineResponse(
        view.getId(),
        view.getBankAccountId(),
        view.getLineDate(),
        view.getAmountMinor(),
        view.getCurrency(),
        view.getDescription(),
        view.getReference(),
        view.getStatus(),
        view.getReconciledCategory(),
        view.getJournalEntryId());
  }
}
