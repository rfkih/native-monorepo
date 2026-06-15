package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link StatutoryRule}. Derived queries only; RLS-scoped (rule 5). The run
 * resolves the SINGLE effective rule per rule_key for the period's as-of date (the freeze step).
 */
public interface StatutoryRuleRepository extends JpaRepository<StatutoryRule, UUID> {

  /** Every active statutory rule effective on the as-of date (one per rule_key is expected). */
  List<StatutoryRule> findByActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** A single rule_key's effective row on the as-of date. */
  Optional<StatutoryRule>
      findByRuleKeyAndActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
          String ruleKey, LocalDate from, LocalDate to);
}
