package id.co.nativeapp.finance.stocktake.messaging;

/**
 * A {@code StocktakeCompleted} record that can never be processed: undecodable Avro bytes OR an
 * event that fails {@link StocktakeCompletedEvent#assertValid()} (ADR 0038 phase 3). Registered as
 * NON-RETRYABLE in {@code KafkaConfig} so the poison record routes straight to {@code
 * StocktakeCompleted.DLT} instead of blocking the partition — money is preserved for inspection,
 * never posted from a contradictory/dangerous figure.
 */
public class StocktakeCompletedDecodeException extends RuntimeException {

  public StocktakeCompletedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
