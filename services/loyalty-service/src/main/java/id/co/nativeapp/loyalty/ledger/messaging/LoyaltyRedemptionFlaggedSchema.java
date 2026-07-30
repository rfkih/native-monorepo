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
 * Loads loyalty-service's PRODUCER copy of the {@code LoyaltyRedemptionFlagged} Avro schema from
 * the classpath ({@code avro/LoyaltyRedemptionFlagged.avsc}, shipped by {@code libs/contracts}) and
 * builds the {@link GenericRecord}s emitted when an eventual-consistency overdraft is detected (ADR
 * 0027): a vertical accepted a points/gift-card redemption against its stale local cache, but the
 * authoritative ledger went negative. NOT a reversal — a manual-follow-up flag.
 *
 * <p>Exactly one of ({@code memberId}, {@code giftCardId}) and the matching shortfall is non-null
 * per the catalog contract (a flag is either a points OR a gift-card overdraft, never both).
 */
public final class LoyaltyRedemptionFlaggedSchema {

  public static final String RESOURCE = "avro/LoyaltyRedemptionFlagged.avsc";
  public static final String EVENT_TYPE = "LoyaltyRedemptionFlagged";
  public static final String AGGREGATE_TYPE = "loyalty_redemption_flag";

  private static final Schema SCHEMA = parse();

  private LoyaltyRedemptionFlaggedSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /** Builds a points-overdraft flag ({@code memberId} non-null, {@code giftCardId} null). */
  public static GenericRecord forPoints(
      UUID flagId,
      String companyId,
      UUID businessId,
      UUID saleId,
      UUID memberId,
      long shortfallPoints,
      Instant occurredAt) {
    return build(
        flagId, companyId, businessId, saleId, memberId, null, null, shortfallPoints, occurredAt);
  }

  /** Builds a gift-card-overdraft flag ({@code giftCardId} non-null, {@code memberId} null). */
  public static GenericRecord forGiftCard(
      UUID flagId,
      String companyId,
      UUID businessId,
      UUID saleId,
      UUID giftCardId,
      long shortfallMinor,
      Instant occurredAt) {
    return build(
        flagId, companyId, businessId, saleId, null, giftCardId, shortfallMinor, null, occurredAt);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static GenericRecord build(
      UUID flagId,
      String companyId,
      UUID businessId,
      UUID saleId,
      UUID memberId,
      UUID giftCardId,
      Long shortfallMinor,
      Long shortfallPoints,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("flag_id", flagId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("sale_id", saleId.toString());
    record.put("member_id", memberId == null ? null : memberId.toString());
    record.put("gift_card_id", giftCardId == null ? null : giftCardId.toString());
    record.put("shortfall_minor", shortfallMinor);
    record.put("shortfall_points", shortfallPoints);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        LoyaltyRedemptionFlaggedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
