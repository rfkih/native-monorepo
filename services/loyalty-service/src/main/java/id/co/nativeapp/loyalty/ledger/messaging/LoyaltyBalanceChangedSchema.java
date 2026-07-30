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
 * Loads loyalty-service's PRODUCER copy of the {@code LoyaltyBalanceChanged} Avro schema from the
 * classpath ({@code avro/LoyaltyBalanceChanged.avsc}, shipped by {@code libs/contracts}) and builds
 * the {@link GenericRecord}s emitted whenever a member's points balance changes (ADR 0027).
 *
 * <p>Carries the ABSOLUTE resulting {@code points_balance} (never a delta) plus the monotonic
 * {@code balance_seq} — see {@link id.co.nativeapp.loyalty.member.domain.LoyaltyMember#applyPointsDelta}.
 * NO PII (rule 6): {@code member_id} is an opaque UUID.
 */
public final class LoyaltyBalanceChangedSchema {

  /** Classpath location of the {@code .avsc}, shared with every consumer via {@code libs/contracts}. */
  public static final String RESOURCE = "avro/LoyaltyBalanceChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "LoyaltyBalanceChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "loyalty_member";

  private static final Schema SCHEMA = parse();

  private LoyaltyBalanceChangedSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code LoyaltyBalanceChanged} record.
   *
   * @param memberId the loyalty member (partition key)
   * @param companyId the owning tenant
   * @param pointsBalance the resulting ABSOLUTE balance
   * @param balanceSeq the resulting monotonic sequence
   * @param reason {@code ENROLLED|EARNED|REDEEMED|ADJUSTED|REVERSED}
   * @param occurredAt when the balance change occurred
   */
  public static GenericRecord toRecord(
      UUID memberId,
      String companyId,
      long pointsBalance,
      long balanceSeq,
      String reason,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("member_id", memberId.toString());
    record.put("company_id", companyId);
    record.put("points_balance", pointsBalance);
    record.put("balance_seq", balanceSeq);
    record.put("reason", reason);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        LoyaltyBalanceChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
