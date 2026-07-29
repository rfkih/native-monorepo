package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.Vendor;
import id.co.nativeapp.finance.ap.projection.VendorView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link Vendor} aggregate. Inherited {@code findById}/{@code save} are the
 * write path (whole aggregate); the native-query {@link VendorView} projections are the read path
 * (only the needed columns — never {@code SELECT *} of the entity). All access is RLS-scoped to the
 * bound tenant (no manual {@code WHERE company_id}).
 */
public interface VendorRepository extends JpaRepository<Vendor, UUID> {

  /**
   * All vendors in the bound tenant, ordered by name — the vendor list read. Bounded at 500
   * (mirroring AR's code-review M3) as an interim guard against an unbounded scan; a paginated
   * envelope is a tracked follow-up.
   */
  @Query(
      value = "SELECT id, name, email, tax_id, active FROM vendor ORDER BY name LIMIT 500",
      nativeQuery = true)
  List<VendorView> findAllView();

  /** One vendor by id in the bound tenant (RLS-scoped) — the vendor detail read. */
  @Query(
      value = "SELECT id, name, email, tax_id, active FROM vendor WHERE id = :id",
      nativeQuery = true)
  Optional<VendorView> findViewById(UUID id);
}
