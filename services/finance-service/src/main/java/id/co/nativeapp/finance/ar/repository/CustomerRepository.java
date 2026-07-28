package id.co.nativeapp.finance.ar.repository;

import id.co.nativeapp.finance.ar.domain.Customer;
import id.co.nativeapp.finance.ar.projection.CustomerView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository for the {@link Customer} aggregate. Inherited {@code findById}/{@code save} are the
 * write path (whole aggregate); the native-query {@link CustomerView} projections are the read path
 * (only the needed columns — never {@code SELECT *} of the entity). All access is RLS-scoped to the
 * bound tenant (no manual {@code WHERE company_id}).
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

  /**
   * All customers in the bound tenant, ordered by name — the customer list read. Bounded at 500
   * (code-review M3) as an interim guard against an unbounded scan; a paginated envelope is a
   * tracked follow-up.
   */
  @Query(
      value = "SELECT id, name, email, tax_id, active FROM customer ORDER BY name LIMIT 500",
      nativeQuery = true)
  List<CustomerView> findAllView();

  /** One customer by id in the bound tenant (RLS-scoped) — the customer detail read. */
  @Query(
      value = "SELECT id, name, email, tax_id, active FROM customer WHERE id = :id",
      nativeQuery = true)
  Optional<CustomerView> findViewById(UUID id);
}
