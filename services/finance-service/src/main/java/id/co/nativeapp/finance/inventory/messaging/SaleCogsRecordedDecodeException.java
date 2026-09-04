package id.co.nativeapp.finance.inventory.messaging;

/**
 * A {@code SaleCogsRecorded} record that can never be processed: undecodable Avro bytes OR an event
 * that fails {@link SaleCogsRecordedEvent#assertValid()} (ADR 0067 Phase C). Registered as
 * NON-RETRYABLE in {@code KafkaConfig} so the poison record routes straight to {@code
 * SaleCogsRecorded.DLT} instead of blocking the partition — money is preserved for inspection,
 * never posted from a contradictory/dangerous figure.
 */
public class SaleCogsRecordedDecodeException extends RuntimeException {

  public SaleCogsRecordedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
