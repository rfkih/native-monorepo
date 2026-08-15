package id.co.nativeapp.restaurant.register.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
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

  /**
   * One non-cash tender's reconciliation carried on the event (ADR 0038 phase 2) — the assembled
   * form the writer passes to {@link #toRecord}, mapped 1:1 to the Avro {@code
   * TenderReconciliation} array element. {@code overShortMinor} is SIGNED {@code counted −
   * expected}.
   *
   * @param tenderType {@code CARD} | {@code QRIS} | {@code ONLINE} (never CASH)
   * @param expectedMinor Σ sales − Σ refunds for the tender in the window, minor units
   * @param countedMinor the cashier's counted/settled figure, minor units (≥ 0)
   * @param overShortMinor SIGNED {@code counted − expected}
   */
  public record TenderLine(
      String tenderType, long expectedMinor, long countedMinor, long overShortMinor) {}

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
   * @param tenders the non-cash per-tender reconciliation lines (CARD/QRIS/ONLINE) counted at close
   *     — empty on a cash-only close (ADR 0038 phase 2)
   * @param supersedesEventId ADR 0064 correction marker — {@code null} on an original close; the
   *     prior close/correction outbox event id this corrected snapshot supersedes (finance reverses
   *     that variance and posts this one in its place)
   * @param closeSeq 1 for the original close, +1 per correction
   * @param reason the manager/owner's correction reason ({@code null} on an original close)
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
      String currency,
      List<TenderLine> tenders,
      UUID supersedesEventId,
      int closeSeq,
      String reason) {
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
    // ADR 0064 correction fields (null / 1 / null on an original close).
    record.put(
        "supersedes_event_id", supersedesEventId == null ? null : supersedesEventId.toString());
    record.put("close_seq", closeSeq);
    record.put("reason", reason);

    Schema tendersSchema = SCHEMA.getField("tenders").schema();
    Schema itemSchema = tendersSchema.getElementType();
    GenericData.Array<GenericRecord> tenderArray =
        new GenericData.Array<>(tenders.size(), tendersSchema);
    for (TenderLine line : tenders) {
      GenericRecord item = new GenericData.Record(itemSchema);
      item.put("tender_type", line.tenderType());
      item.put("expected_minor", line.expectedMinor());
      item.put("counted_minor", line.countedMinor());
      item.put("over_short_minor", line.overShortMinor());
      tenderArray.add(item);
    }
    record.put("tenders", tenderArray);
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
