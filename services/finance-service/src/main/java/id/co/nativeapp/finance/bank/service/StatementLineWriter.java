package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankAccount;
import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import id.co.nativeapp.finance.bank.repository.BankStatementLineRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for importing {@link BankStatementLine}s for one {@link
 * BankAccount}. A distinct proxy-invoked bean so the {@code @Transactional} advice + RLS aspect
 * apply (rule 5). Validates the bank account exists in THIS tenant, then saves every line
 * UNRECONCILED, stamped with the account's own (immutable) currency — never a client-supplied one.
 */
@Component
public class StatementLineWriter {

  private final BankAccountRepository bankAccountRepository;
  private final BankStatementLineRepository statementLineRepository;

  public StatementLineWriter(
      BankAccountRepository bankAccountRepository,
      BankStatementLineRepository statementLineRepository) {
    this.bankAccountRepository =
        Objects.requireNonNull(bankAccountRepository, "bankAccountRepository");
    this.statementLineRepository =
        Objects.requireNonNull(statementLineRepository, "statementLineRepository");
  }

  /**
   * Imports {@code inputs} as UNRECONCILED lines for {@code bankAccountId}. Returns the imported
   * lines as response DTOs (never the entity), in the same order as the input.
   *
   * @throws BankAccountNotFoundException if the bank account is not in the bound tenant
   * @throws IllegalArgumentException if {@code inputs} is empty, or any line's amount is zero
   */
  @Transactional
  public List<StatementLineResponse> importLines(
      UUID bankAccountId, List<StatementLineInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new IllegalArgumentException("at least one statement line is required");
    }
    BankAccount account =
        bankAccountRepository
            .findById(bankAccountId)
            .orElseThrow(() -> new BankAccountNotFoundException(bankAccountId));

    String companyId = TenantContext.require().companyId();
    List<StatementLineResponse> imported = new ArrayList<>(inputs.size());
    for (StatementLineInput input : inputs) {
      BankStatementLine line =
          BankStatementLine.of(
              account.getId(),
              input.lineDate(),
              input.amountMinor(),
              account.getCurrency(),
              input.description(),
              input.reference());
      line.setCompanyId(companyId);
      imported.add(toResponse(statementLineRepository.save(line)));
    }
    return imported;
  }

  private static StatementLineResponse toResponse(BankStatementLine line) {
    return new StatementLineResponse(
        line.getId(),
        line.getBankAccountId(),
        line.getLineDate(),
        line.getAmountMinor(),
        line.getCurrency(),
        line.getDescription(),
        line.getReference(),
        line.getStatus().name(),
        line.getReconciledCategory() == null ? null : line.getReconciledCategory().name(),
        line.getJournalEntryId());
  }
}
