package id.co.nativeapp.loyalty.ledger.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads loyalty-service's PRODUCER copy of the {@code GiftCardStateChanged} Avro schema from the
 * classpath ({@code avro/GiftCardStateChanged.avsc}, shipped by {@code libs/contracts}) and builds
 * the {@link GenericRecord}s emitted whenever a gift card's state or balance changes (ADR 0027).
 *
 * <p>Carries the ABSOLUTE resulting {@code balance_minor} (never a delta) plus the monotonic
 * {@code balance_seq}. Money is an integer minor-units amount + ISO-4217 currency, never a float
 * (rule 8).
 */
public final class GiftCardStateChangedSchema {

  public static final String RESOURCE = "avro/GiftCardStateChanged.avsc";
  public static final String EVENT_TYPE = "GiftCardStateChanged";
  public static final String AGGREGATE_TYPE = "gift_card";

  private static final Schema SCHEMA = parse();

  private GiftCardStateChangedSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code GiftCardStateChanged} record.
   *
   * @param giftCardId the gift card (partition key)
   * @param companyId the owning tenant
   * @param state {@code ACTIVE|DEPLETED|EXPIRED|VOID}
   * @param balanceMinor the resulting ABSOLUTE stored-value balance, minor units
   * @param currency ISO-4217 currency code
   * @param balanceSeq the resulting monotonic sequence
   * @param occurredAt when the state/balance change occurred
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID giftCardId,
      String companyId,
      String state,
      long balanceMinor,
      String currency,
      long balanceSeq,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("gift_card_id", giftCardId.toString());
    record.put("company_id", companyId);
    record.put("state", state);
    record.put("balance_minor", balanceMinor);
    record.put("currency", currency);
    record.put("balance_seq", balanceSeq);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        GiftCardStateChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
