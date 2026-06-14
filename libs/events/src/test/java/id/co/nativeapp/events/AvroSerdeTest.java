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
}
