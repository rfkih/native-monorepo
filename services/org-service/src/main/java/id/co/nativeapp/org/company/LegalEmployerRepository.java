package id.co.nativeapp.org.company;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LegalEmployer}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} on the connection automatically and the PostgreSQL RLS policy restricts
 * results to the bound company (rule 5).
 */
public interface LegalEmployerRepository extends JpaRepository<LegalEmployer, UUID> {}
