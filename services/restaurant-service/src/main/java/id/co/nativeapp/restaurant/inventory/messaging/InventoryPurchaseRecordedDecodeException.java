package id.co.nativeapp.restaurant.inventory.messaging;

/**
 * An {@code InventoryPurchaseRecorded} payload that cannot be decoded against the shared schema — a
 * contract violation, non-retryable: the listener's error handler routes it to {@code
 * InventoryPurchaseRecorded.DLT} (fail closed, never silently dropped).
 */
public class InventoryPurchaseRecordedDecodeException extends RuntimeException {

  public InventoryPurchaseRecordedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
