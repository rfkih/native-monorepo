package id.co.nativeapp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion (3): the Avro serde round-trips a record, and the compatibility checker
 * accepts a backward-compatible schema change and rejects an incompatible one.
 *
 * <p>No concrete Native event schema exists yet (SaleRecorded arrives later), so these tests use a
 * small inline schema modelled on a money-carrying event: an integer minor-units amount plus an
 * ISO-4217 currency code (never a float).
 */
class AvroSerdeTest {

  // v1: the original event schema.
  private static final Schema V1 =
      new Schema.Parser()
          .parse(
              """
                            {
                              "type": "record",
                              "name": "SaleRecorded",
                              "namespace": "id.co.nativeapp.events.test",
                              "fields": [
                                {"name": "saleId", "type": "string"},
                                {"name": "amountMinor", "type": "long"},
                                {"name": "currencyCode", "type": "string"}
                              ]
                            }
                            """);

  // v2-compatible: adds an optional field WITH a default -> a reader on v2 can still read v1
  // data (the new field falls back to its default).
  private static final Schema V2_COMPATIBLE =
      new Schema.Parser()
          .parse(
              """
                            {
                              "type": "record",
                              "name": "SaleRecorded",
                              "namespace": "id.co.nativeapp.events.test",
                              "fields": [
                                {"name": "saleId", "type": "string"},
                                {"name": "amountMinor", "type": "long"},
                                {"name": "currencyCode", "type": "string"},
                                {"name": "note", "type": ["null", "string"], "default": null}
                              ]
                            }
                            """);

  // v2-incompatible: adds a REQUIRED field with NO default -> a reader on this schema cannot
  // read v1 data, because there is no value for the new field.
  private static final Schema V2_INCOMPATIBLE =
      new Schema.Parser()
          .parse(
              """
                            {
                              "type": "record",
                              "name": "SaleRecorded",
                              "namespace": "id.co.nativeapp.events.test",
                              "fields": [
                                {"name": "saleId", "type": "string"},
                                {"name": "amountMinor", "type": "long"},
                                {"name": "currencyCode", "type": "string"},
                                {"name": "cashierId", "type": "string"}
                              ]
                            }
                            """);

  @Test
  void serializeThenDeserializeRoundTrips() {
    GenericRecord record = new GenericData.Record(V1);
    record.put("saleId", "sale-123");
    record.put("amountMinor", 1_234_500L); // money is integer minor units, never a float
    record.put("currencyCode", "IDR");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, V1);

    assertEquals("sale-123", decoded.get("saleId").toString());
    assertEquals(1_234_500L, decoded.get("amountMinor"));
    assertEquals("IDR", decoded.get("currencyCode").toString());
  }

  @Test
  void backwardCompatibleSchemaReadsOldData() {
    // Write with v1, read with the backward-compatible v2 reader schema.
    GenericRecord v1Record = new GenericData.Record(V1);
    v1Record.put("saleId", "sale-9");
    v1Record.put("amountMinor", 500L);
    v1Record.put("currencyCode", "USD");
    byte[] bytes = AvroSerde.serialize(v1Record);

    GenericRecord decoded = AvroSerde.deserialize(bytes, V1, V2_COMPATIBLE);

    assertEquals("sale-9", decoded.get("saleId").toString());
    assertEquals(500L, decoded.get("amountMinor"));
    assertEquals("USD", decoded.get("currencyCode").toString());
    assertNull(decoded.get("note"), "new optional field must default to null");
  }

  @Test
  void compatibilityCheckerAcceptsBackwardCompatibleChange() {
    assertTrue(
        AvroSerde.isBackwardCompatible(V1, V2_COMPATIBLE),
        "adding an optional field with a default is backward-compatible");
  }

  @Test
  void compatibilityCheckerRejectsIncompatibleChange() {
    assertFalse(
        AvroSerde.isBackwardCompatible(V1, V2_INCOMPATIBLE),
        "adding a required field with no default breaks backward compatibility");
  }

  @Test
  void identitySchemaIsBackwardCompatibleWithItself() {
    assertTrue(AvroSerde.isBackwardCompatible(V1, V1));
  }

  // ---- replay tolerance: a payload written before a trailing field was appended ----------------

  /** A record BEFORE a nullable, defaulted field was appended. */
  private static final Schema BEFORE_APPEND =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "Evolving",
                "namespace": "id.co.nativeapp.events.test",
                "fields": [
                  {"name": "id", "type": "string"},
                  {"name": "amount_minor", "type": "long"}
                ]
              }
              """);

  /** The same record AFTER two appends, each nullable with a default (the fleet convention). */
  private static final Schema AFTER_APPEND =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "Evolving",
                "namespace": "id.co.nativeapp.events.test",
                "fields": [
                  {"name": "id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": ["null", "string"], "default": null},
                  {"name": "vertical", "type": ["null", "string"], "default": null}
                ]
              }
              """);

  /** A record whose appended trailing field is REQUIRED — a breaking change, not a valid append. */
  private static final Schema AFTER_REQUIRED_APPEND =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "Evolving",
                "namespace": "id.co.nativeapp.events.test",
                "fields": [
                  {"name": "id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "reason", "type": "string"}
                ]
              }
              """);

  private static byte[] oldPayload() {
    GenericRecord old = new GenericData.Record(BEFORE_APPEND);
    old.put("id", "evt-1");
    old.put("amount_minor", 250_000L);
    return AvroSerde.serialize(old);
  }

  @Test
  void aPayloadWrittenBeforeAnAppendStillDecodesWithTheNewSchema() {
    // The production consumer path: writer == reader == the consumer's CURRENT schema. Without
    // replay tolerance this runs off the end of the buffer and DLTs every historical event.
    GenericRecord decoded = AvroSerde.deserialize(oldPayload(), AFTER_APPEND);

    assertEquals("evt-1", decoded.get("id").toString());
    assertEquals(250_000L, decoded.get("amount_minor"));
    // Absent on the wire → filled from the declared default.
    assertNull(decoded.get("currency"));
    assertNull(decoded.get("vertical"));
  }

  @Test
  void aCurrentPayloadIsUnaffectedByTheFallback() {
    GenericRecord current = new GenericData.Record(AFTER_APPEND);
    current.put("id", "evt-2");
    current.put("amount_minor", 1L);
    current.put("currency", "IDR");
    current.put("vertical", "restaurant");

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(current), AFTER_APPEND);

    assertEquals("IDR", decoded.get("currency").toString());
    assertEquals("restaurant", decoded.get("vertical").toString());
  }

  @Test
  void aCorruptPayloadStillFailsClosed() {
    // The fallback must not turn a genuinely broken payload into a silent partial decode: only
    // trailing DEFAULTED fields are ever dropped, and if no prefix decodes the original throws.
    byte[] garbage = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> AvroSerde.deserialize(garbage, AFTER_APPEND));
  }

  @Test
  void aTrailingREQUIREDFieldIsNeverDroppedByTheFallback() {
    // Appending a required field is a breaking change (isBackwardCompatible rejects it). The
    // fallback must not paper over one by decoding an old payload as if it were fine.
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> AvroSerde.deserialize(oldPayload(), AFTER_REQUIRED_APPEND));
  }
}
