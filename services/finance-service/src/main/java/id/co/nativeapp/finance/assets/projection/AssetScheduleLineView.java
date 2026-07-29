package id.co.nativeapp.finance.assets.projection;

/**
 * Read projection over one posted schedule line of an item (Phase 6) — the run's period, the
 * schedule month index, and the posted amount. Backs the asset-detail schedule history.
 */
public interface AssetScheduleLineView {

  String getPeriod();

  int getMonthIndex();

  long getAmountMinor();

  String getCurrency();
}
