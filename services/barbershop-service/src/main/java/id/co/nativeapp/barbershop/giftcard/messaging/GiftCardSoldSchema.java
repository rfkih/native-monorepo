package id.co.nativeapp.barbershop.giftcard.messaging;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.barbershop.giftcard.domain.GiftCardSale;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads barbershop-service's PRODUCER copy of the {@code GiftCardSold} Avro schema from the
 * classpath ({@code avro/GiftCardSold.avsc}, the single {@code libs/contracts} source of truth, ADR
 * 0003) and builds {@link GenericRecord}s from a persisted {@link GiftCardSale}.
 *
 * <p>NOT a sale — a LIABILITY event (V17 migration comment): {@code amount_minor} is the
 * stored-value amount loaded onto the card, never revenue. Never merged into {@code SaleRecorded}.
 */
public final class GiftCardSoldSchema {

  public static final String RESOURCE = "avro/GiftCardSold.avsc";
  public static final String EVENT_TYPE = "GiftCardSold";
  public static final String AGGREGATE_TYPE = "gift_card";

  private static final Schema SCHEMA = parse();

  private GiftCardSoldSchema() {
    // static holder
  }

  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code GiftCardSold} {@link GenericRecord} from a persisted {@link GiftCardSale}.
   *
   * @param sale the persisted gift-card-sale record
   * @param companyId the owning tenant (stamped on the sale from the tenant scope)
   */
  public static GenericRecord toRecord(GiftCardSale sale, String companyId) {
    Money amount = sale.getAmount();
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("gift_card_sale_id", sale.getId().toString());
    record.put("gift_card_id", sale.getGiftCardId().toString());
    record.put("company_id", companyId);
    record.put("business_id", sale.getBusinessId().toString());
    record.put("amount_minor", amount.amountMinor());
    record.put("currency", amount.currency().getCurrencyCode());
    record.put("tender_type", sale.getTenderType());
    record.put("occurred_at", sale.getOccurredAt().toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in = GiftCardSoldSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
