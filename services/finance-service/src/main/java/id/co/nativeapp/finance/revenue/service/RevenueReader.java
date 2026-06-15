package id.co.nativeapp.finance.revenue.service;

import id.co.nativeapp.finance.revenue.domain.ConsolidatedRevenue;
import id.co.nativeapp.finance.revenue.repository.ConsolidatedRevenueRepository;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the consolidated-revenue read model for the bound tenant — the query side of M1.5.
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * the lookup carries NO {@code WHERE company_id}; the result set is constrained solely by the
 * auto-applied RLS policy (rule 5), so a caller only ever sees their own tenant's revenue. The read
 * runs under whatever tenant the {@link id.co.nativeapp.tenant.TenantContext TenantContext} scope
 * is bound to (set at the request edge by the gateway / dev filter).
 */
@Service
public class RevenueReader {

  private final ConsolidatedRevenueRepository revenueRepository;

  public RevenueReader(ConsolidatedRevenueRepository revenueRepository) {
    this.revenueRepository = revenueRepository;
  }

  /**
   * The consolidated revenue for a period (bound tenant). M1.5 is single base currency, so a period
   * has at most one currency row; this returns that accumulator's total as {@link Money}, or empty
   * if nothing has been posted for the period yet (the controller renders that as a zero total in
   * the requested currency).
   *
   * @throws IllegalStateException if a period somehow holds more than one currency (would require
   *     FX to sum, which M1.5 deliberately does not have)
   */
  @Transactional(readOnly = true)
  public Optional<Money> revenueForPeriod(String period) {
    List<ConsolidatedRevenue> rows = revenueRepository.findByPeriod(period);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() > 1) {
      // Single base currency in M1.5; multiple currency rows for one period would need
      // FX to consolidate into one figure, which is explicitly out of scope. Fail loudly
      // rather than silently pick one.
      throw new IllegalStateException(
          "Multiple currencies for period " + period + "; FX consolidation is not in M1.5 scope");
    }
    return Optional.of(rows.getFirst().getTotal());
  }
}
