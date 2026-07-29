package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.domain.StatementLineNotFoundException;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.projection.StatementLineView;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import id.co.nativeapp.finance.bank.repository.BankStatementLineRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads bank statement lines for the bound tenant — the query side of the statement-line slice.
 * {@code @Transactional(readOnly = true)} so the proxy + auto-RLS aspect engage; the reads carry no
 * manual {@code WHERE company_id} (RLS scopes them, rule 5), and the projection type never leaves
 * this layer.
 */
@Service
public class StatementLineReader {

  private final BankAccountRepository bankAccountRepository;
  private final BankStatementLineRepository statementLineRepository;

  public StatementLineReader(
      BankAccountRepository bankAccountRepository,
      BankStatementLineRepository statementLineRepository) {
    this.bankAccountRepository = bankAccountRepository;
    this.statementLineRepository = statementLineRepository;
  }

  /**
   * The statement lines for one bank account, optionally filtered by {@code status}.
   *
   * @throws BankAccountNotFoundException if the bank account is not in the bound tenant
   */
  @Transactional(readOnly = true)
  public List<StatementLineResponse> list(UUID bankAccountId, String status) {
    bankAccountRepository
        .findViewById(bankAccountId)
        .orElseThrow(() -> new BankAccountNotFoundException(bankAccountId));
    return statementLineRepository.findByBankAccount(bankAccountId, status).stream()
        .map(StatementLineReader::toResponse)
        .toList();
  }

  /**
   * One statement line by id.
   *
   * @throws StatementLineNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public StatementLineResponse get(UUID lineId) {
    return statementLineRepository
        .findViewById(lineId)
        .map(StatementLineReader::toResponse)
        .orElseThrow(() -> new StatementLineNotFoundException(lineId));
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
