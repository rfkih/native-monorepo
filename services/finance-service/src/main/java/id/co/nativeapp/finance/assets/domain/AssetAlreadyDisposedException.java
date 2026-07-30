package id.co.nativeapp.finance.assets.domain;

import java.util.UUID;

/**
 * A dispose request hit an asset that is already DISPOSED under a DIFFERENT Idempotency-Key (a
 * genuine second disposal attempt, not a retry — retries replay via the stored key). Mapped to 409
 * {@code asset-already-disposed} by the advice; a disposal is one-shot and has no reversal flow in
 * this slice (ADR 0022).
 */
public class AssetAlreadyDisposedException extends RuntimeException {

  public AssetAlreadyDisposedException(UUID assetId) {
    super("asset " + assetId + " is already disposed — a disposal cannot be repeated or amended");
  }
}
