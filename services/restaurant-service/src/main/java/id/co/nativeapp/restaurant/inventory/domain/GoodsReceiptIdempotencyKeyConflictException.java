package id.co.nativeapp.restaurant.inventory.domain;

/**
 * A replayed priced-receive {@code Idempotency-Key} whose request payload DIFFERS from the original
 * (ADR 0067 Phase D, D1; engineering standard §1.3: same key + different payload → 409). Prevents a
 * client bug (or a genuinely different receive that accidentally reused a key) from silently
 * masking a changed ingredient/quantity/value behind a 200 replay. Mapped to {@code 409 Conflict}
 * ({@code goods-receipt-idempotency-key-conflict}) by {@code IngredientAdvice}.
 */
public class GoodsReceiptIdempotencyKeyConflictException extends RuntimeException {

  public GoodsReceiptIdempotencyKeyConflictException(String message) {
    super(message);
  }
}
