package id.co.nativeapp.finance.empexpense.repository;

import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseClaimLedger;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link EmployeeExpenseClaimLedger} per-claim row (ADR 0030 §7
 * settle-once guard + §4 drill-down source).
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} unit of work (rule 5). The
 * derived {@link #findByClaimId(UUID)} query is the single lookup every writer (approval, void,
 * settlement) uses to find-or-create the row; the {@code UNIQUE (company_id, claim_id)} index (V39)
 * is the concurrency backstop a concurrent racer's INSERT trips as a {@code
 * DataIntegrityViolationException}.
 */
public interface EmployeeExpenseClaimLedgerRepository
    extends JpaRepository<EmployeeExpenseClaimLedger, UUID> {

  Optional<EmployeeExpenseClaimLedger> findByClaimId(UUID claimId);
}
