package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.PayComponent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link PayComponent} (pure config). Derived queries only; RLS-scoped (rule
 * 5).
 */
public interface PayComponentRepository extends JpaRepository<PayComponent, UUID> {

  /** The active components in display order (the calculation walks them in this order). */
  List<PayComponent> findByActiveTrueOrderByDisplayOrderAsc();

  /** A component by its key (within the bound tenant). */
  Optional<PayComponent> findByComponentKey(String componentKey);
}
