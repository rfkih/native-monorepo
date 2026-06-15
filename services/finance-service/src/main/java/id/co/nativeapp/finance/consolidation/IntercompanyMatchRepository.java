package id.co.nativeapp.finance.consolidation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for the {@link IntercompanyMatch} reconciliation rows (P3d SEAM 3a).
 *
 * <p>Tenant AND group scoping come solely from the auto-applied two-GUC conjunction RLS (rule 5).
 */
public interface IntercompanyMatchRepository extends JpaRepository<IntercompanyMatch, UUID> {}
