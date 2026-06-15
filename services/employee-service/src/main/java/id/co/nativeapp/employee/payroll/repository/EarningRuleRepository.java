package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.EarningRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data port for {@link EarningRule}. Derived queries only; RLS-scoped (rule 5). */
public interface EarningRuleRepository extends JpaRepository<EarningRule, UUID> {

  /** Every earning rule bound to a compensation package (within the bound tenant). */
  List<EarningRule> findByCompensationPackageId(UUID compensationPackageId);
}
