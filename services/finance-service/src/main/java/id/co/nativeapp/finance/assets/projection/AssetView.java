package id.co.nativeapp.finance.assets.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection over one asset-register row (Phase 6) — the {@code fixed_asset} columns plus the
 * accumulated-depreciation roll-up (Σ run-line amounts). Book value = cost − accumulated, computed
 * by the reader. Snake_case aliases → these accessors; lives in {@code assets.projection} for the
 * ArchUnit layer rule.
 */
public interface AssetView {

  UUID getId();

  String getName();

  LocalDate getAcquisitionDate();

  String getStartPeriod();

  long getCostMinor();

  long getSalvageMinor();

  int getUsefulLifeMonths();

  String getCurrency();

  String getStatus();

  long getAccumulatedMinor();
}
