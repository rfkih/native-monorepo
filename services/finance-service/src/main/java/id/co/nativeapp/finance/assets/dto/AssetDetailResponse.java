package id.co.nativeapp.finance.assets.dto;

import java.util.List;

/**
 * An asset's detail (Phase 6): the register row plus its posted schedule history (one line per run
 * that included it).
 */
public record AssetDetailResponse(AssetResponse asset, List<ScheduleLine> schedule) {

  /** One posted schedule step: the run's period, the schedule month index, and the amount. */
  public record ScheduleLine(String period, int monthIndex, long amountMinor, String currency) {}
}
