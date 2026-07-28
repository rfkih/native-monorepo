package id.co.nativeapp.finance.unitpnl.service;

import id.co.nativeapp.finance.unitpnl.dto.UnitPnlResponse;
import id.co.nativeapp.finance.unitpnl.projection.UnitPnlRowView;
import id.co.nativeapp.finance.unitpnl.repository.UnitPnlRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the per-org-unit P&amp;L rollup for the bound tenant — the query side of the org-unit hub.
 *
 * <p>A {@code @Transactional(readOnly = true)} service bean so the tx proxy + auto-RLS aspect
 * engage: the query carries NO {@code WHERE company_id}; RLS alone scopes it (rule 5). The
 * projection rows are mapped to the {@link UnitPnlResponse} DTO inside this method so the
 * controller never depends on the projection type (ArchUnit projection-layer rule).
 */
@Service
public class UnitPnlReader {

  private final UnitPnlRepository unitPnlRepository;

  public UnitPnlReader(UnitPnlRepository unitPnlRepository) {
    this.unitPnlRepository = Objects.requireNonNull(unitPnlRepository, "unitPnlRepository");
  }

  /**
   * The rollup for {@code orgUnitId} in {@code period}: the unit's own postings plus, for a
   * business unit, its child outlets' — with the per-outlet breakdown.
   *
   * <p>Returns {@link Optional#empty()} when the unit is unknown to the cached org tree (which,
   * under RLS, includes any foreign tenant's unit — anti-enumeration) or is a {@code team} (teams
   * never carry postings and have no P&amp;L). A known unit with zero postings returns zeros with a
   * {@code null} currency — the controller decides between the currency-hint substitution and 204.
   *
   * @throws IllegalStateException if postings under the unit mix currencies — impossible while
   *     M1.5's single-base-currency invariant holds; failing loud beats mis-summing money
   */
  @Transactional(readOnly = true)
  public Optional<UnitPnlResponse> unitPnlForPeriod(UUID orgUnitId, String period) {
    List<UnitPnlRowView> rows = unitPnlRepository.rollupForPeriod(orgUnitId, period);
    if (rows.isEmpty()) {
      return Optional.empty();
    }

    UnitPnlRowView self =
        rows.stream().filter(r -> r.getOrgUnitId().equals(orgUnitId)).findFirst().orElse(null);
    if (self == null || "TEAM".equalsIgnoreCase(self.getType())) {
      // parent_id matched but the unit itself is gone (should not happen — the query's OR
      // includes the self row), or the unit is a team: no P&L surface.
      return Optional.empty();
    }

    long revenue = 0L;
    long expense = 0L;
    boolean illustrative = false;
    String currency = null;
    List<UnitPnlResponse.OutletPnlRow> outlets = new ArrayList<>();

    for (UnitPnlRowView row : rows) {
      if (row.getRevenueCurrencyCount() > 1 || row.getExpenseCurrencyCount() > 1) {
        throw new IllegalStateException(
            "Mixed posting currencies under org unit "
                + orgUnitId
                + " for period "
                + period
                + " — single-base-currency invariant violated");
      }
      revenue = Math.addExact(revenue, row.getRevenueMinor());
      expense = Math.addExact(expense, row.getExpenseMinor());
      illustrative = illustrative || Boolean.TRUE.equals(row.getUsesIllustrativeRules());
      currency = mergeCurrency(currency, row.getRevenueCurrency(), orgUnitId, period);
      currency = mergeCurrency(currency, row.getExpenseCurrency(), orgUnitId, period);
      if (!row.getOrgUnitId().equals(orgUnitId)) {
        outlets.add(
            new UnitPnlResponse.OutletPnlRow(
                row.getOrgUnitId(),
                row.getName(),
                row.getActive(),
                row.getRevenueMinor(),
                row.getExpenseMinor(),
                Math.subtractExact(row.getRevenueMinor(), row.getExpenseMinor())));
      }
    }

    return Optional.of(
        new UnitPnlResponse(
            orgUnitId,
            period,
            revenue,
            expense,
            Math.subtractExact(revenue, expense),
            currency,
            illustrative,
            List.copyOf(outlets)));
  }

  /** Folds one leg's currency into the running one; a disagreement fails loud (M1.5). */
  private static String mergeCurrency(
      String current, String candidate, UUID orgUnitId, String period) {
    if (candidate == null) {
      return current;
    }
    String stripped = candidate.strip();
    if (current != null && !current.equals(stripped)) {
      throw new IllegalStateException(
          "Mixed posting currencies across org units under " + orgUnitId + " for period " + period);
    }
    return stripped;
  }
}
