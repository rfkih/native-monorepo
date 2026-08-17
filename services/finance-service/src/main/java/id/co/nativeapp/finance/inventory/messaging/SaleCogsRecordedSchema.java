package id.co.nativeapp.finance.inventory.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads finance-service's reader view of the {@code SaleCogsRecorded} contract from the classpath
 * ({@code avro/SaleCogsRecorded.avsc}, single-sourced from libs/contracts — ADR 0003). Will be
 * consumed to post perpetual COGS: {@code Dr 5100 COGS / Cr 1100 Inventory} WHEN the company is
 * perpetual-active for {@code occurred_at}'s period, otherwise a claimed no-op (ADR 0067 §1).
 * Because the dashboard P&L is GL-derived (ADR 0065), the {@code 5100} leg reaches the beranda and
 * the income statement automatically once this posts. Wire bytes are raw Avro (libs/events {@code
 * AvroSerde}), deduped by the event UUID and by {@code sale_id} UNIQUE — no Schema Registry serde.
 *
 * <p>ADR 0067 Phase 0 (contracts-first, this class): schema registration + catalog + contract tests
 * only. The {@code decode(...)} + event record land with the {@code SaleCogsRecorded}-consuming
 * listener (Phase C).
 */
public final class SaleCogsRecordedSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/SaleCogsRecorded.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "SaleCogsRecorded";

  private static final Schema SCHEMA = parse();

  private SaleCogsRecordedSchema() {
    // static holder
  }

  /** The parsed reader schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        SaleCogsRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
