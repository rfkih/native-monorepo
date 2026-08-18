package id.co.nativeapp.restaurant.inventory.projection;

import java.util.UUID;

/**
 * Read projection for the idempotency-replay probe on {@code goods_receipt} (ADR 0067 Phase D, D1)
 * — backs the native lookup on {@code GoodsReceiptRepository#findReplayByIdempotencyKey}. Only the
 * fields needed to (a) detect a same-key/different-payload conflict and (b) locate the ingredient
 * whose current state should be returned on a same-key/same-payload replay.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface GoodsReceiptReplayView {

  UUID getIngredientId();

  long getQty();

  long getValueMinor();

  String getCurrency();
}
