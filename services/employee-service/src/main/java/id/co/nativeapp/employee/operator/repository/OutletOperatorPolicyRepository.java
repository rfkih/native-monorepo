package id.co.nativeapp.employee.operator.repository;

import id.co.nativeapp.employee.operator.domain.OutletOperatorPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link OutletOperatorPolicy} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC on every {@code @Transactional} method (rule 5). A lookup
 * that resolves to another tenant's policy row is invisible under RLS (returns empty), so a
 * cross-tenant read fails closed without any hand-written predicate.
 */
public interface OutletOperatorPolicyRepository extends JpaRepository<OutletOperatorPolicy, UUID> {

  /** The operator-PIN policy row for an outlet (within the bound tenant), if one has been set. */
  Optional<OutletOperatorPolicy> findByBusinessId(UUID businessId);
}
