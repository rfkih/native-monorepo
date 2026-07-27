package id.co.nativeapp.finance.withinclose.service;

import id.co.nativeapp.finance.withinclose.dto.CloseHistoryResponse;
import id.co.nativeapp.finance.withinclose.repository.WithinCompanyCloseRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the {@code GET /api/v1/closes} path.
 *
 * <p>A separate {@code @Component} (not a private method on {@link WithinCompanyCloseService}) so
 * the read-only transaction is invoked through the Spring proxy — a self-invocation would bypass
 * the {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC,
 * which is the load-bearing mechanism that scopes the query to the bound company (rule 5).
 *
 * <p>{@code readOnly = true} signals PostgreSQL that no write will follow, allowing the driver to
 * skip acquiring a write lock and a replica to serve the read.
 */
@Component
public class CloseHistoryReader {

  private final WithinCompanyCloseRepository closeRepository;

  public CloseHistoryReader(WithinCompanyCloseRepository closeRepository) {
    this.closeRepository = closeRepository;
  }

  /**
   * All close records for the bound company, most-recent period first. No {@code WHERE company_id}
   * — RLS constrains the result set (rule 5). Projection-to-DTO mapping happens here in the service
   * layer (projection is not exposed to the controller — CODE-STRUCTURE §3.3).
   *
   * @return the close history for the current tenant
   */
  @Transactional(readOnly = true)
  public List<CloseHistoryResponse> findAllForCurrentTenant() {
    return closeRepository.findAllViews().stream()
        .map(
            v ->
                new CloseHistoryResponse(
                    v.getId(),
                    v.getPeriod(),
                    v.getBaseCurrency().strip(),
                    true, // every persisted row is a real first close
                    v.isReconciled(),
                    v.isUsesIllustrativeRules()))
        .toList();
  }
}
