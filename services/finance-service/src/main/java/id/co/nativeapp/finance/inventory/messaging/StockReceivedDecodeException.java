package id.co.nativeapp.finance.inventory.messaging;

/**
 * A {@code StockReceived} record that can never be processed: undecodable Avro bytes OR an event
 * that fails {@link StockReceivedEvent#assertValid()} (ADR 0067 Phase B). Registered as
 * NON-RETRYABLE in {@code KafkaConfig} so the poison record routes straight to {@code
 * StockReceived.DLT} instead of blocking the partition — money is preserved for inspection, never
 * posted from a contradictory/dangerous figure.
 */
public class StockReceivedDecodeException extends RuntimeException {

  public StockReceivedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
