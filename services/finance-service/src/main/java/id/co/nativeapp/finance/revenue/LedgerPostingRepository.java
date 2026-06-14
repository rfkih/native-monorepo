package id.co.nativeapp.finance.revenue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the append-only {@link LedgerPosting} ledger.
 *
 * <p>A thin data port: derived queries only, no business logic, no {@code Money} arithmetic, no
 * manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS GUC on
 * every {@code @Transactional} method (rule 5). {@link #findBySourceEventId(UUID)} keys on the
 * event UUID (a {@code UNIQUE} column), used as the belt-and-braces idempotency check alongside the
 * {@code ProcessedEventStore}.
 */
public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {

  /** Looks up the posting produced by a given source event (the UNIQUE idempotency key). */
  Optional<LedgerPosting> findBySourceEventId(UUID sourceEventId);
}
