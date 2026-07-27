package id.co.nativeapp.finance.outlet.service;

import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.projection.OutletRevenueView;
import id.co.nativeapp.finance.outlet.repository.OutletRevenueRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the per-outlet net-revenue read model for the bound tenant — the query side of the outlet
 * revenue slice.
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage:
 * the lookup carries NO {@code WHERE company_id}; the result set is constrained solely by the
 * auto-applied RLS policy (rule 5), so a caller only ever sees their own tenant's outlet rows. The
 * projection rows are mapped to the {@link OutletRevenueResponse} DTO inside this method so the
 * controller layer never depends on the {@link OutletRevenueView} projection type directly (the
 * ArchUnit projection-layer rule allows projections only in service and repository layers).
 */
@Service
public class OutletRevenueReader {

  private final OutletRevenueRepository outletRevenueRepository;

  public OutletRevenueReader(OutletRevenueRepository outletRevenueRepository) {
    this.outletRevenueRepository = outletRevenueRepository;
  }

  /**
   * All per-outlet accumulators for a period (bound tenant), mapped to a {@link
   * OutletRevenueResponse} with rows ordered by revenue descending. Returns {@link
   * Optional#empty()} when the period has no postings yet — the controller renders that as {@code
   * 204 No Content}.
   *
   * <p>The currency is read from the first row (all rows in one tenant+period share the same
   * currency in M1.5 — single base currency). The projection type is never exposed outside this
   * layer.
   */
  @Transactional(readOnly = true)
  public Optional<OutletRevenueResponse> outletsForPeriod(String period) {
    List<OutletRevenueView> rows = outletRevenueRepository.findByPeriodOrderByRevenueDesc(period);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    // All rows for the same tenant+period share one currency (single base currency, M1.5).
    String currency = rows.getFirst().getCurrency().strip();
    List<OutletRevenueResponse.OutletRow> outlets =
        rows.stream()
            .map(
                r ->
                    new OutletRevenueResponse.OutletRow(
                        r.getBusinessId(), r.getRevenueMinor(), r.getOutletName()))
            .toList();
    return Optional.of(new OutletRevenueResponse(period, currency, outlets));
  }
}
