package id.co.nativeapp.finance.revenue.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's consumer view of the {@code SaleRecorded} Avro schema from the classpath
 * ({@code avro/SaleRecorded.avsc}, sourced from {@code libs/contracts} — ADR 0003) and decodes raw
 * Avro bytes into a {@link SaleRecordedEvent} — no Avro code-generation plugin, and no Confluent /
 * Schema Registry serde. finance owns its consumer decode path (full name {@code
 * id.co.nativeapp.events.restaurant.SaleRecorded}, matching docs/EVENT-CATALOG.md); a contract test
 * asserts this schema stays backward-compatible with itself across changes.
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — consistent with how the outbox stores
 * events.
 *
 * <p><strong>ADR 0006 slice 2 addition:</strong> the {@code tender_type} nullable field is decoded
 * and threaded into {@link SaleRecordedEvent} so {@link
 * id.co.nativeapp.finance.revenue.service.RevenuePostingWriter} can route the GL clearing account
 * by tender ({@code null}/{@code "CASH"} → CASH_CLEARING, {@code "QRIS"} → QRIS_CLEARING, {@code
 * "CARD"} → CARD_CLEARING). Old events (carwash, legacy direct-sales) have {@code tender_type =
 * null} and remain routed to CASH_CLEARING unchanged.
 */
public final class SaleRecordedSchema {

  /** Classpath location of the consumer-copy {@code .avsc} (from libs/contracts). */
  public static final String RESOURCE = "avro/SaleRecorded.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "SaleRecorded";

  private static final Schema SCHEMA = parse();

  private SaleRecordedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code SaleRecorded} (finance's consumer view). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * SaleRecordedEvent}, using this consumer view of the schema as both writer and reader schema.
   *
   * <p>The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float); {@code occurred_at} is epoch millis
   * UTC. The optional {@code tender_type} (nullable string) is decoded — {@code null} for old
   * events / carwash; the enum name string for POS sales. The optional Phase 2 breakdown fields are
   * decoded — all {@code null} for legacy producers (carwash, direct-sale path).
   *
   * <p><strong>Phase 4 (ADR 0027) loyalty/gift-card fields.</strong> The five trailing optional
   * fields ({@code loyalty_member_id}, {@code loyalty_redeemed_points}, {@code
   * loyalty_redeemed_minor}, {@code gift_card_id}, {@code gift_card_redeemed_minor}) are decoded
   * the same way as the Phase 2 breakdown fields — {@code null} for a pre-Phase-4 producer (the
   * Avro union defaults to {@code null} when the field is absent from the writer schema), so a
   * pre-Phase-4 event decodes into a {@link SaleRecordedEvent} with all five fields {@code null},
   * byte-identical to before Phase 4.
   *
   * <p><strong>Phase B (ADR 0036) {@code channel} field.</strong> The trailing optional {@code
   * channel} field (the sales-channel code for an ONLINE-tender sale) is decoded the same way —
   * {@code null} for every producer in this wave (no producer threads a real channel yet; that
   * lands in Phase B2) and for any pre-Phase-B producer (old-writer/new-reader).
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static SaleRecordedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    // sale_id: the producing sale aggregate UUID — used by reversal writer for per-leg lookup.
    UUID saleId = UUID.fromString(record.get("sale_id").toString());
    // tender_type is ["null","string"] with default null; old events have it absent (reads as
    // null).
    Object tenderTypeRaw = record.get("tender_type");
    String tenderType = (tenderTypeRaw != null) ? tenderTypeRaw.toString() : null;
    // Phase 2 breakdown fields — all ["null", ...] with default null; old events read as null.
    Long subtotalMinor = (Long) record.get("subtotal_minor");
    Long discountMinor = (Long) record.get("discount_minor");
    Long serviceChargeMinor = (Long) record.get("service_charge_minor");
    Long taxMinor = (Long) record.get("tax_minor");
    Object taxRuleVersionRaw = record.get("tax_rule_version");
    String taxRuleVersion = (taxRuleVersionRaw != null) ? taxRuleVersionRaw.toString() : null;
    Boolean usesIllustrative = (Boolean) record.get("uses_illustrative_rules");
    // Phase 4 (ADR 0027) loyalty/gift-card fields — all ["null", ...] with default null; a
    // pre-Phase-4 producer's bytes decode with every one of these as null (old-writer/new-reader).
    Object loyaltyMemberIdRaw = record.get("loyalty_member_id");
    String loyaltyMemberId = (loyaltyMemberIdRaw != null) ? loyaltyMemberIdRaw.toString() : null;
    Long loyaltyRedeemedPoints = (Long) record.get("loyalty_redeemed_points");
    Long loyaltyRedeemedMinor = (Long) record.get("loyalty_redeemed_minor");
    Object giftCardIdRaw = record.get("gift_card_id");
    String giftCardId = (giftCardIdRaw != null) ? giftCardIdRaw.toString() : null;
    Long giftCardRedeemedMinor = (Long) record.get("gift_card_redeemed_minor");
    // Phase B (ADR 0036): ["null","string"] with default null; absent from pre-Phase-B producer
    // bytes decodes as null (old-writer/new-reader), and no producer in this wave sets it.
    Object channelRaw = record.get("channel");
    String channel = (channelRaw != null) ? channelRaw.toString() : null;
    return new SaleRecordedEvent(
        eventId,
        saleId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        amount,
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        tenderType,
        subtotalMinor,
        discountMinor,
        serviceChargeMinor,
        taxMinor,
        taxRuleVersion,
        usesIllustrative,
        loyaltyMemberId,
        loyaltyRedeemedPoints,
        loyaltyRedeemedMinor,
        giftCardId,
        giftCardRedeemedMinor,
        channel);
  }

  private static Schema parse() {
    try (InputStream in = SaleRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
