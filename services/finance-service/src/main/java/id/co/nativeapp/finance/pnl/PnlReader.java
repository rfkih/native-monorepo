package id.co.nativeapp.finance.pnl;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the consolidated P&amp;L read model for the bound tenant — the query side of the P&amp;L
 * (#21).
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * the lookup carries NO {@code WHERE company_id}; the result set is constrained solely by the
 * auto-applied RLS policy (rule 5), so a caller only ever sees their own tenant's P&amp;L. The read
 * runs under whatever tenant the {@link id.co.nativeapp.tenant.TenantContext} scope is bound to
 * (set at the request edge by the gateway / dev filter).
 */
@Service
public class PnlReader {

  private final ConsolidatedPnlRepository pnlRepository;

  public PnlReader(ConsolidatedPnlRepository pnlRepository) {
    this.pnlRepository = pnlRepository;
  }

  /**
   * The consolidated P&amp;L (revenue, expense, net) for a period (bound tenant), or empty if
   * nothing has been posted for the period yet. Single base currency, so a period has at most one
   * currency row.
   *
   * @throws IllegalStateException if a period somehow holds more than one currency (would require
   *     FX to consolidate into one figure, which is out of scope here)
   */
  @Transactional(readOnly = true)
  public Optional<ConsolidatedPnl> pnlForPeriod(String period) {
    List<ConsolidatedPnl> rows = pnlRepository.findByPeriod(period);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() > 1) {
      throw new IllegalStateException(
          "Multiple currencies for period " + period + "; FX consolidation is not in scope");
    }
    return Optional.of(rows.getFirst());
  }
}
