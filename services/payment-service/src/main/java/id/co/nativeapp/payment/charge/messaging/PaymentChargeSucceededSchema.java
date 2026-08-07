package id.co.nativeapp.payment.charge.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code PaymentChargeSucceeded} Avro schema from the classpath ({@code
 * avro/PaymentChargeSucceeded.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation, matching every other schema holder. The schema is single-sourced in {@code
 * libs/contracts} (ADR 0003) and registered in {@code docs/EVENT-CATALOG.md}.
 *
 * <p>Emitted when a dynamic-QRIS gateway charge (ADR 0045) reaches SUCCEEDED — from the signed
 * Midtrans webhook or the status-sync fallback — in the SAME transaction as the status transition
 * (rule 3). The POS vertical named in {@code vertical} consumes it and runs its EXISTING idempotent
 * capture writer; a mismatch parks, an already-captured payment no-ops. Money is integer minor
 * units + ISO-4217 (rule 8); no PII rides the event (rule 6).
 */
public final class PaymentChargeSucceededSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/PaymentChargeSucceeded.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "PaymentChargeSucceeded";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "payment_charge";

  private static final Schema SCHEMA = parse();

  private PaymentChargeSucceededSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code PaymentChargeSucceeded}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code PaymentChargeSucceeded} record.
   *
   * @param chargeId the payment_charge id (partition key)
   * @param companyId the owning tenant
   * @param vertical {@code restaurant} | {@code carwash} | {@code barbershop} (lowercase)
   * @param paymentId the vertical's payment row id (the verification anchor)
   * @param referenceId the vertical's capture key when different (carwash/barbershop ticket id);
   *     {@code null} for restaurant
   * @param businessId the outlet the charge was rung at
   * @param amountMinor the charged amount, minor units (the tender residual)
   * @param currency ISO-4217 code ({@code IDR})
   * @param provider the settling PSP ({@code MIDTRANS})
   * @param providerTxnId the provider's transaction id, or {@code null} if omitted
   * @param succeededAt when the settlement was recorded
   */
  public static GenericRecord toRecord(
      UUID chargeId,
      String companyId,
      String vertical,
      UUID paymentId,
      UUID referenceId,
      UUID businessId,
      long amountMinor,
      String currency,
      String provider,
      String providerTxnId,
      Instant succeededAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("charge_id", chargeId.toString());
    record.put("company_id", companyId);
    record.put("vertical", vertical);
    record.put("payment_id", paymentId.toString());
    record.put("reference_id", referenceId == null ? null : referenceId.toString());
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("provider", provider);
    record.put("provider_txn_id", providerTxnId);
    record.put("succeeded_at", succeededAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        PaymentChargeSucceededSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
