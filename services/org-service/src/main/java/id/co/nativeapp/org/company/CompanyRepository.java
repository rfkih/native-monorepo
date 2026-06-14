package id.co.nativeapp.org.company;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Company}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...} and no call to apply
 * the tenant GUC: every Spring Data method is transactional, so {@link
 * id.co.nativeapp.org.config.RlsAutoApplyAspect} sets {@code app.current_tenant} on the connection
 * automatically and the PostgreSQL RLS policy restricts results to the bound company (correctness
 * by default — rule 5). A company is its own tenant, so a {@code findById} only ever returns the
 * company whose id is the bound tenant.
 */
public interface CompanyRepository extends JpaRepository<Company, UUID> {}
