package id.co.nativeapp.finance.register.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/**
 * Finance-side (consumer) holder for the {@code RegisterSessionClosed} Avro schema — parses the
 * single-source {@code avro/RegisterSessionClosed.avsc} from the classpath ({@code libs/contracts},
 * ADR 0003). The producer is restaurant-service's register feature (closing kasir, ADR 0035);
 * finance consumes it to post ONLY the signed cash variance (selisih kas) that trues CASH_CLEARING
 * to the physically counted drawer.
 */
public final class RegisterSessionClosedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/RegisterSessionClosed.avsc";

  private static final Schema SCHEMA = parse();

  private RegisterSessionClosedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code RegisterSessionClosed}. */
  public static Schema schema() {
    return SCHEMA;
  }

  private static Schema parse() {
    try (InputStream in =
        RegisterSessionClosedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
