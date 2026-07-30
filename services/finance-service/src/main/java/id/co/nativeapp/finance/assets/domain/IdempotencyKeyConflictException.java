package id.co.nativeapp.finance.assets.domain;

import java.util.UUID;

/**
 * A dispose request reused an Idempotency-Key that previously disposed a DIFFERENT asset (ADR
 * 0022). The path {@code {id}} is authoritative: silently replaying the other asset's disposal
 * would tell the caller a money command succeeded while never touching the asset they named. Mapped
 * to 409 {@code asset-idempotency-key-conflict}; the client must retry with a fresh key.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

  public IdempotencyKeyConflictException(UUID requestedAssetId, UUID replayedAssetId) {
    super(
        "the Idempotency-Key was already used to dispose asset "
            + replayedAssetId
            + ", not the requested asset "
            + requestedAssetId
            + " — use a fresh key per disposal attempt");
  }
}
