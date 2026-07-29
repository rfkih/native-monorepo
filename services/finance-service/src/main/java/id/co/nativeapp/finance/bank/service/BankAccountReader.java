package id.co.nativeapp.finance.bank.service;

import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.projection.BankAccountView;
import id.co.nativeapp.finance.bank.repository.BankAccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads bank accounts for the bound tenant — the query side of the bank-account slice.
 * {@code @Transactional(readOnly = true)} so the proxy + auto-RLS aspect engage; the reads carry no
 * manual {@code WHERE company_id} (RLS scopes them, rule 5), and the projection type never leaves
 * this layer. Mirrors {@code VendorReader}.
 */
@Service
public class BankAccountReader {

  private final BankAccountRepository bankAccountRepository;

  public BankAccountReader(BankAccountRepository bankAccountRepository) {
    this.bankAccountRepository = bankAccountRepository;
  }

  /** All bank accounts in the bound tenant, ordered by name. */
  @Transactional(readOnly = true)
  public List<BankAccountResponse> list() {
    return bankAccountRepository.findAllView().stream().map(BankAccountReader::toResponse).toList();
  }

  /**
   * One bank account by id.
   *
   * @throws BankAccountNotFoundException if not in the bound tenant (RLS-scoped)
   */
  @Transactional(readOnly = true)
  public BankAccountResponse get(UUID id) {
    return bankAccountRepository
        .findViewById(id)
        .map(BankAccountReader::toResponse)
        .orElseThrow(() -> new BankAccountNotFoundException(id));
  }

  private static BankAccountResponse toResponse(BankAccountView view) {
    return new BankAccountResponse(
        view.getId(),
        view.getName(),
        view.getAccountNumber(),
        view.getCurrency(),
        view.getActive());
  }
}
