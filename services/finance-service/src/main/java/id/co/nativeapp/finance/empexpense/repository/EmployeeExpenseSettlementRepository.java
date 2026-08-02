package id.co.nativeapp.finance.empexpense.repository;

import id.co.nativeapp.finance.empexpense.domain.EmployeeExpenseSettlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link EmployeeExpenseSettlement} settle-once guard (ADR 0030 §7).
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} unit of work (rule 5). The
 * derived {@link #existsByClaimId(UUID)} query is used both for the writer's own fast pre-check and
 * for the service-layer conflict-recovery re-check (mirrors {@code
 * LoyaltyMemberRepository#existsByPhoneHash}); the {@code UNIQUE (company_id, claim_id)} index
 * (V39) is the concurrency backstop a concurrent racer's insert trips as a {@code
 * DataIntegrityViolationException}.
 */
public interface EmployeeExpenseSettlementRepository
    extends JpaRepository<EmployeeExpenseSettlement, UUID> {

  boolean existsByClaimId(UUID claimId);
}
