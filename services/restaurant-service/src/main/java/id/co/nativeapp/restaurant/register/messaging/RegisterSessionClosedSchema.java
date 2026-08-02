package id.co.nativeapp.restaurant.register.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code RegisterSessionClosed} Avro schema from the classpath ({@code
 * avro/RegisterSessionClosed.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation, matching every other schema holder. The schema is single-sourced in {@code
 * libs/contracts} (ADR 0003) and registered in {@code docs/EVENT-CATALOG.md}.
 *
 * <p>Emitted when a cash-register session (closing kasir, ADR 0036) is CLOSED: finance posts ONLY
 * the signed cash variance ({@code over_short_minor = counted − expected}) — revenue was already
 * recognized at sale time by {@code SaleRecorded}. Every amount is integer minor units in one
 * currency (rule 8). The producer computes {@code expected_cash_minor} server-side; the consumer
 * re-asserts both reconciliation identities and DLTs on violation, so this builder is the single
 * place the identities are assembled.
 */
public final class RegisterSessionClosedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/RegisterSessionClosed.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "RegisterSessionClosed";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "register_session";

  private static final Schema SCHEMA = parse();

  private RegisterSessionClosedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code RegisterSessionClosed}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code RegisterSessionClosed} record. All amounts are minor units in {@code currency}.
   *
   * @param sessionId the cash_register_session id (partition key)
   * @param companyId the owning tenant
   * @param businessId the outlet whose drawer was counted (a real OUTLET id — ADR 0012)
   * @param openedAt session open instant (the cash window is {@code [openedAt, closedAt)})
   * @param closedAt session close instant (finance posts into {@code periodOf(closedAt)})
   * @param openingFloatMinor the change fund at open (≥ 0)
   * @param cashSalesMinor Σ CASH-tender sale amounts in the window
   * @param cashRefundsMinor Σ CASH refunds paid from the drawer in the window (≥ 0)
   * @param expectedCashMinor server-computed {@code float + sales − refunds}
   * @param countedCashMinor the cashier's physical whole-drawer count (≥ 0)
   * @param overShortMinor SIGNED {@code counted − expected} (negative = short, positive = over)
   * @param currency ISO-4217 code shared by every amount
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID sessionId,
      String companyId,
      UUID businessId,
      Instant openedAt,
      Instant closedAt,
      long openingFloatMinor,
      long cashSalesMinor,
      long cashRefundsMinor,
      long expectedCashMinor,
      long countedCashMinor,
      long overShortMinor,
      String currency) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("session_id", sessionId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("opened_at", openedAt.toEpochMilli());
    record.put("closed_at", closedAt.toEpochMilli());
    record.put("opening_float_minor", openingFloatMinor);
    record.put("cash_sales_minor", cashSalesMinor);
    record.put("cash_refunds_minor", cashRefundsMinor);
    record.put("expected_cash_minor", expectedCashMinor);
    record.put("counted_cash_minor", countedCashMinor);
    record.put("over_short_minor", overShortMinor);
    record.put("currency", currency);
    return record;
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
