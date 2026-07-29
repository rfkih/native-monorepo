package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankAccount;
import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for {@link BankAccount} create/update. A distinct bean
 * (not a private method on a service) so it is invoked through the Spring proxy — the
 * {@code @Transactional} advice and the {@code RlsAutoApplyAspect} that binds the tenant GUC only
 * apply through the proxy, and the GUC is what makes the RLS {@code WITH CHECK} pass on the insert
 * (rule 5). The tenant is bound at the request edge (gateway → security filter → {@link
 * TenantContext}). Mirrors {@code VendorWriter}.
 */
@Component
public class BankAccountWriter {

  private final BankAccountRepository bankAccountRepository;

  public BankAccountWriter(BankAccountRepository bankAccountRepository) {
    this.bankAccountRepository =
        Objects.requireNonNull(bankAccountRepository, "bankAccountRepository");
  }

  /**
   * Creates an active bank account in the bound tenant. Returns the response DTO (never the
   * entity).
   */
  @Transactional
  public BankAccountResponse create(String name, String accountNumber, String currency) {
    BankAccount account = BankAccount.create(name, accountNumber, currency);
    account.setCompanyId(TenantContext.require().companyId());
    return toResponse(bankAccountRepository.save(account));
  }

  /**
   * Updates a bank account's mutable details (name/account number) and/or active flag. A null
   * {@code active} leaves the flag unchanged. The currency is immutable and not settable here.
   *
   * @throws BankAccountNotFoundException if the id is not in the bound tenant (RLS-scoped)
   */
  @Transactional
  public BankAccountResponse update(
      UUID bankAccountId, String name, String accountNumber, Boolean active) {
    BankAccount account =
        bankAccountRepository
            .findById(bankAccountId)
            .orElseThrow(() -> new BankAccountNotFoundException(bankAccountId));
    account.updateDetails(name, accountNumber);
    if (active != null) {
      account.setActive(active);
    }
    return toResponse(bankAccountRepository.save(account));
  }

  private static BankAccountResponse toResponse(BankAccount account) {
    return new BankAccountResponse(
        account.getId(),
        account.getName(),
        account.getAccountNumber(),
        account.getCurrency(),
        account.isActive());
  }
}
