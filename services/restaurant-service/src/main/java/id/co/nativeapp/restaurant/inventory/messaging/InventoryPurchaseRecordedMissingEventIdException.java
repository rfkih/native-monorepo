package id.co.nativeapp.restaurant.inventory.messaging;

/**
 * An {@code InventoryPurchaseRecorded} record without a valid {@code id} header — the durable event
 * UUID every consumer dedupes on. A contract violation, non-retryable: routed to {@code
 * InventoryPurchaseRecorded.DLT} (fail closed).
 */
public class InventoryPurchaseRecordedMissingEventIdException extends RuntimeException {

  public InventoryPurchaseRecordedMissingEventIdException(String message) {
    super(message);
  }

  public InventoryPurchaseRecordedMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
