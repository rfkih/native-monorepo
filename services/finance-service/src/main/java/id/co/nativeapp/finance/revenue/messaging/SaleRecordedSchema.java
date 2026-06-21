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
        usesIllustrative);
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
