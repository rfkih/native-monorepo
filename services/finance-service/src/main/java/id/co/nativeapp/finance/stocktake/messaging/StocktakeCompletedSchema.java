package id.co.nativeapp.finance.stocktake.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Finance-side (consumer) holder for the {@code StocktakeCompleted} Avro schema — parses the
 * single-source {@code avro/StocktakeCompleted.avsc} from the classpath ({@code libs/contracts},
 * ADR 0003). The producer is restaurant-service's inventory feature (a physical stocktake submitted
 * at the daily close, ADR 0038 phase 3); finance consumes it to post ONLY the signed net valued
 * shrinkage (selisih stok), never re-touching revenue/COGS-on-sale.
 */
public final class StocktakeCompletedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/StocktakeCompleted.avsc";

  /** The Kafka topic — the Debezium outbox router names topics exactly after the event_type. */
  public static final String TOPIC = "StocktakeCompleted";

  private static final Schema SCHEMA = parse();

  private StocktakeCompletedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code StocktakeCompleted}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /** Decodes the raw outbox Avro payload into the typed event (see AvroSerde). */
  public static StocktakeCompletedEvent decode(java.util.UUID eventId, byte[] payload) {
    return StocktakeCompletedEvent.from(
        eventId, id.co.nativeapp.events.AvroSerde.deserialize(payload, SCHEMA));
  }

  private static Schema parse() {
    try (InputStream in =
        StocktakeCompletedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
