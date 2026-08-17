package id.co.nativeapp.finance.inventory.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Loads finance-service's reader view of the {@code StockReceived} contract from the classpath
 * ({@code avro/StockReceived.avsc}, single-sourced from libs/contracts — ADR 0003). Will be
 * consumed to capitalize a priced goods receipt: {@code Dr 1100 Inventory / Cr 2050 GRNI Clearing}
 * WHEN the company is perpetual-active for {@code received_at}'s period, otherwise a claimed no-op
 * (ADR 0067 §1/§2). Wire bytes are raw Avro (libs/events {@code AvroSerde}), deduped by the event
 * UUID and by {@code receipt_id} (via {@code journal_entry.source_event_id} UNIQUE) — no Schema
 * Registry serde.
 *
 * <p>ADR 0067 Phase 0 (contracts-first, this class): schema registration + catalog + contract tests
 * only. The {@code decode(...)} + event record land with the {@code StockReceived}-consuming
 * listener (Phase B).
 */
public final class StockReceivedSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/StockReceived.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "StockReceived";

  private static final Schema SCHEMA = parse();

  private StockReceivedSchema() {
    // static holder
  }

  /** The parsed reader schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        StockReceivedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
