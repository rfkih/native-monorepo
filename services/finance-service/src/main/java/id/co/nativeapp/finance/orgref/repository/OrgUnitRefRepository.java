package id.co.nativeapp.finance.orgref.repository;

import id.co.nativeapp.finance.orgref.domain.OrgUnitRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read/write port for the {@code org_unit_ref} local read model.
 *
 * <p>A thin data port. The write path (upsert) is an atomic {@code INSERT … ON CONFLICT … DO
 * UPDATE} executed directly via {@code JdbcTemplate} in {@link
 * id.co.nativeapp.finance.orgref.service.OrgUnitRefWriter} — no find-or-create here. This
 * repository exists to back the Spring Data {@code findById} on the write path (checking for an
 * existing row) and any future read queries; the {@link OrgUnitRef} entity is the JPA-mapping of
 * the table.
 *
 * <p>Tenant scoping comes solely from the auto-applied RLS GUC on every {@code @Transactional}
 * method (rule 5); there is no manual {@code WHERE company_id} in any query. The consumer writes
 * inside a {@link id.co.nativeapp.tenant.TenantContext} scope bound to the event's {@code
 * company_id}.
 */
public interface OrgUnitRefRepository extends JpaRepository<OrgUnitRef, UUID> {}
