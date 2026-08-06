package id.co.nativeapp.restaurant.register.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * Request to close a drawer session with the cashier's physical counts (ADR 0036; per-tender counts
 * added in ADR 0038 phase 2).
 *
 * @param countedCashMinor the whole-drawer CASH count INCLUDING the float, minor units (≥ 0) —
 *     expected cash and the cash variance are server-computed
 * @param tenderCounts optional counted/settled amounts for the NON-CASH tenders (CARD/QRIS/ONLINE)
 *     — each is reconciled against the server-computed expected for that tender; a tender omitted
 *     here is simply not settled at this close (empty list on a cash-only close)
 */
public record CloseSessionRequest(
    @NotNull @PositiveOrZero Long countedCashMinor, @Valid List<TenderCount> tenderCounts) {

  /** Cash-only close — the pre-ADR-0038 shape, kept so existing call sites don't churn. */
  public CloseSessionRequest(Long countedCashMinor) {
    this(countedCashMinor, List.of());
  }

  /** Never null — an absent {@code tenderCounts} is an empty list (a cash-only close). */
  public List<TenderCount> tenderCounts() {
    return tenderCounts == null ? List.of() : tenderCounts;
  }
}
