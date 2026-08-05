package id.co.nativeapp.finance.assets.domain;

/**
 * A brought-forward asset registration's opening period is sealed — a {@code tax_filing} row exists
 * for it (mirrors {@code OpeningBalanceSealedPeriodException}, ADR 0037) — so it cannot post into
 * that period. Console registers brought-forward assets BEFORE the main opening-balance entry;
 * without this guard a sealed as-of date would silently restate the sealed month and then the main
 * opening entry would 422, stranding a half-completed migration. Mapped to {@code 422} {@code
 * asset-sealed-period}.
 */
public class AssetSealedPeriodException extends RuntimeException {

  public AssetSealedPeriodException(String message) {
    super(message);
  }
}
