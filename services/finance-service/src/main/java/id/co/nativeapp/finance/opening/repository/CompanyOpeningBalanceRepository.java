package id.co.nativeapp.finance.opening.repository;

import id.co.nativeapp.finance.opening.domain.CompanyOpeningBalance;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link CompanyOpeningBalance} (ADR 0037). No manual {@code WHERE
 * company_id} — RLS applies the tenant scope (rule 5), so both the replay probe and {@code count()}
 * see only this company's (at most one) row. The write path loads the whole entity to verify the
 * replay payload (the PlatformSettlementRepository idiom).
 */
public interface CompanyOpeningBalanceRepository
    extends JpaRepository<CompanyOpeningBalance, UUID> {

  /** Replay probe — the marker previously recorded under this Idempotency-Key, if any. */
  Optional<CompanyOpeningBalance> findByIdempotencyKey(String idempotencyKey);
}
