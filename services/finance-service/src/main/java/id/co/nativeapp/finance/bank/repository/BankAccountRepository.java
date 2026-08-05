package id.co.nativeapp.finance.bank.repository;

import id.co.nativeapp.finance.bank.domain.BankAccount;
import id.co.nativeapp.finance.bank.projection.BankAccountView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link BankAccount} aggregate. Inherited {@code findById}/{@code save} are the
 * write path (whole aggregate); the native-query {@link BankAccountView} projections are the read
 * path (only the needed columns — never {@code SELECT *} of the entity). All access is RLS-scoped
 * to the bound tenant (no manual {@code WHERE company_id}).
 */
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

  /**
   * All bank accounts in the bound tenant, ordered by name — the bank-account list read. Bounded at
   * 500 (mirroring AR/AP's code-review-driven LIMIT convention) as an interim guard against an
   * unbounded scan; a paginated envelope is a tracked follow-up.
   */
  @Query(
      value =
          "SELECT id, name, bank_name AS bank_name, account_number AS account_number, currency,"
              + " active FROM bank_account ORDER BY name LIMIT 500",
      nativeQuery = true)
  List<BankAccountView> findAllView();

  /** One bank account by id in the bound tenant (RLS-scoped) — the bank-account detail read. */
  @Query(
      value =
          "SELECT id, name, bank_name AS bank_name, account_number AS account_number, currency,"
              + " active FROM bank_account WHERE id = :id",
      nativeQuery = true)
  Optional<BankAccountView> findViewById(UUID id);
}
