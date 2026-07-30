package id.co.nativeapp.finance.assets.domain;

/**
 * A dispose request found the asset's depreciation out of step with the disposal's posting period
 * (ADR 0022): either months due BEFORE the posting period are missing (run the amortization for the
 * gap months first — runs may skip months, so completeness is counted per asset), or a run already
 * posted the asset's depreciation IN or AFTER the posting period (disposing now would need a
 * reversal, which this slice does not do). Mapped to 409 {@code asset-depreciation-behind}.
 */
public class AssetDepreciationBehindException extends RuntimeException {

  public AssetDepreciationBehindException(String message) {
    super(message);
  }
}
