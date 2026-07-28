package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.EarningParamKind;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.projection.CommissionView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data port for {@link EarningRule}. RLS-scoped (rule 5). */
public interface EarningRuleRepository extends JpaRepository<EarningRule, UUID> {

  /** Every earning rule bound to a compensation package (within the bound tenant). */
  List<EarningRule> findByCompensationPackageId(UUID compensationPackageId);

  /** Rules of a given kind on a package — the open-commission duplicate check. */
  List<EarningRule> findByCompensationPackageIdAndParamKind(
      UUID compensationPackageId, EarningParamKind paramKind);

  /**
   * The commission rules of a package as a projection — a native read that selects only the non-PII
   * config columns ({@code fixed_amount_enc} is never touched, so no salary ciphertext is decrypted
   * on this path). Newest first.
   */
  @Query(
      value =
          """
          SELECT er.id                  AS id,
                 er.metric_key          AS metric_key,
                 er.percent_basis_points AS percent_basis_points,
                 er.effective_from      AS effective_from,
                 er.effective_to        AS effective_to
            FROM earning_rule er
           WHERE er.compensation_package_id = :packageId
             AND er.param_kind = 'PERCENT_OF_METRIC'
           ORDER BY er.effective_from DESC
          """,
      nativeQuery = true)
  List<CommissionView> findCommissionViews(@Param("packageId") UUID packageId);
}
