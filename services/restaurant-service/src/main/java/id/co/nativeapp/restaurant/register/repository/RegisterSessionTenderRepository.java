package id.co.nativeapp.restaurant.register.repository;

import id.co.nativeapp.restaurant.register.domain.RegisterSessionTender;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link RegisterSessionTender} (ADR 0038 phase 2) — the per-tender
 * reconciliation lines persisted at close. The close inserts one row per counted non-cash tender;
 * the close-replay path reads them back to verify a reused key carries the same tender counts. RLS
 * applies the tenant scope (rule 5), no manual {@code company_id}.
 */
public interface RegisterSessionTenderRepository
    extends JpaRepository<RegisterSessionTender, UUID> {

  /** The persisted reconciliation lines for a closed session (close-replay payload check). */
  List<RegisterSessionTender> findBySessionId(UUID sessionId);
}
