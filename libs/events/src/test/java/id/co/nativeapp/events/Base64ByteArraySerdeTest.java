package id.co.nativeapp.events;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The base64 Kafka transport layer must round-trip the EXACT raw Avro bytes that {@code AvroSerde}
 * produces, so the {@code @KafkaListener} (and {@code AvroSerde} on the consume side) sees a
 * byte-for-byte identical payload. The wire bytes themselves are the base64 text — this is what
 * Debezium emits with {@code binary.handling.mode=base64}, so every other producer (test producers,
 * StubRelay, DLT re-publish) agrees with the real CDC path.
 */
class Base64ByteArraySerdeTest {

  private static final Schema SCHEMA =
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

  @Test
  void base64RoundTripPreservesRawAvroBytes() {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("saleId", "sale-123");
    record.put("amountMinor", 1_234_500L);
    record.put("currencyCode", "IDR");
    byte[] rawAvro = AvroSerde.serialize(record);

    byte[] onWire;
    try (Base64ByteArraySerializer serializer = new Base64ByteArraySerializer()) {
      onWire = serializer.serialize("SaleRecorded", rawAvro);
    }

    // The wire value IS the base64 text of the Avro bytes (UTF-8) — exactly what Debezium emits.
    assertArrayEquals(Base64.getEncoder().encode(rawAvro), onWire);
    assertNotEquals(
        new String(rawAvro, StandardCharsets.ISO_8859_1),
        new String(onWire, StandardCharsets.ISO_8859_1),
        "the wire bytes must be the base64 text, not the raw Avro bytes");

    byte[] decoded;
    try (Base64ByteArrayDeserializer deserializer = new Base64ByteArrayDeserializer()) {
      decoded = deserializer.deserialize("SaleRecorded", onWire);
    }

    // The listener receives byte-for-byte the same raw Avro the producer serialized.
    assertArrayEquals(rawAvro, decoded);

    GenericRecord backOut = AvroSerde.deserialize(decoded, SCHEMA);
    org.junit.jupiter.api.Assertions.assertEquals("sale-123", backOut.get("saleId").toString());
    org.junit.jupiter.api.Assertions.assertEquals(1_234_500L, backOut.get("amountMinor"));
    org.junit.jupiter.api.Assertions.assertEquals("IDR", backOut.get("currencyCode").toString());
  }

  @Test
  void nullPayloadRoundTripsToNull() {
    try (Base64ByteArraySerializer serializer = new Base64ByteArraySerializer();
        Base64ByteArrayDeserializer deserializer = new Base64ByteArrayDeserializer()) {
      assertNull(serializer.serialize("t", null));
      assertNull(deserializer.deserialize("t", (byte[]) null));
    }
  }
}
