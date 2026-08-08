package id.co.nativeapp.org.devicecredential.repository;

import id.co.nativeapp.org.devicecredential.domain.DeviceCredential;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DeviceCredential}.
 *
 * <p>Carries no manual {@code WHERE company_id = ...}: every method is transactional, so {@link
 * RlsAutoApplyAspect} sets {@code app.current_tenant} automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (rule 5). RLS fails closed — an unscoped read returns zero
 * rows rather than all rows.
 *
 * <p>Both derived-query methods below load the FULL entity (never a projection): the reveal path
 * needs {@code PiiAttributeConverter} to run (a native-query projection would return raw
 * ciphertext, bypassing the converter), and the create/reset/delete paths mutate the aggregate —
 * exactly the write-path exception CODE-STRUCTURE §3.3 carves out for PII-bearing entities (mirrors
 * {@code EmployeeReader#loginDetail}'s {@code findById} for the same reason).
 */
public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, UUID> {

  /** The device credential bound to {@code orgUnitId}, if any. RLS-scoped (rule 5). */
  Optional<DeviceCredential> findByOrgUnitId(UUID orgUnitId);

  /**
   * Whether a device credential already exists for {@code orgUnitId} — the create-path idempotency
   * pre-check (the UNIQUE(company_id, org_unit_id) constraint, V11, is the authoritative guard).
   * RLS-scoped (rule 5).
   */
  boolean existsByOrgUnitId(UUID orgUnitId);
}
