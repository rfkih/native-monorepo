package id.co.nativeapp.finance.assets.domain;

import java.util.UUID;

/**
 * Thrown when a fixed-asset id is not visible in the bound tenant (RLS-scoped). Mapped to {@code
 * 404} with a generic detail — a foreign-tenant id is indistinguishable from a missing one, so there
 * is no existence disclosure (mirrors {@code BillNotFoundException} / {@code
 * TaxFilingNotFoundException}).
 */
public class AssetNotFoundException extends RuntimeException {

  public AssetNotFoundException(UUID assetId) {
    super("fixed asset not found: " + assetId);
  }
}
