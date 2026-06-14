package id.co.nativeapp.employee.payroll;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for the {@link PeriodSeal} projection. Derived queries only; RLS-scoped (rule
 * 5). The completeness gate reads this to confirm every expected source sealed the period.
 */
public interface PeriodSealRepository extends JpaRepository<PeriodSeal, UUID> {

  /** The upsert lookup on the natural key (idempotent re-projection). */
  Optional<PeriodSeal> findByBusinessIdAndPeriod(UUID businessId, String period);

  /** Every seal recorded for a period within the bound tenant. */
  List<PeriodSeal> findByPeriod(String period);
}
