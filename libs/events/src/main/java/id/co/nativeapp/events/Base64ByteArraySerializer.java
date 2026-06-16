package id.co.nativeapp.events;

import java.util.Base64;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Kafka value serializer that base64-encodes the raw Avro {@code byte[]} payload onto the wire (the
 * record value becomes the UTF-8 bytes of the base64 text of those Avro bytes).
 *
 * <p><strong>Why base64 is the wire format and not the raw bytes.</strong> The production transport
 * is the transactional outbox (rule 3) tailed by Debezium. The outbox {@code payload} column is
 * {@code bytea}; Debezium decodes a {@code bytea} value as a {@code java.nio.ByteBuffer}, which
 * Connect's {@code ByteArrayConverter} rejects ("ByteArrayConverter is not compatible with objects
 * of type java.nio.HeapByteBuffer") — the connector task dies and NO event reaches Kafka. The fix
 * is to have Debezium emit the payload as base64 text ({@code binary.handling.mode=base64}) shipped
 * by a {@code StringConverter}: the record value is then the base64 of the Avro bytes (UTF-8). This
 * serializer makes every OTHER producer of these topics (the test producers, the StubRelay, the DLT
 * re-publisher) put exactly that same base64 encoding on the wire, so the wire format is consistent
 * end to end.
 *
 * <p>This is purely a Kafka <em>transport</em> (de)serialization layer. The decoded value handed to
 * the {@code @KafkaListener} is still the raw Avro {@code byte[]}, decoded by {@code AvroSerde}
 * against the consumer's own copy of the schema — there is NO Confluent Schema Registry serde and
 * {@code AvroSerde}'s raw-bytes contract is unchanged.
 *
 * @see Base64ByteArrayDeserializer
 */
public final class Base64ByteArraySerializer implements Serializer<byte[]> {

  private static final Base64.Encoder ENCODER = Base64.getEncoder();

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    // No configuration: the encoding is fixed (RFC 4648 base64, the same alphabet Debezium emits).
  }

  /**
   * Base64-encodes {@code data} (the raw Avro bytes) and returns the UTF-8 bytes of that base64
   * text — the value Kafka stores. A {@code null} payload serializes to {@code null} (Kafka treats
   * it as a tombstone-shaped record; the outbox never produces one).
   */
  @Override
  public byte[] serialize(String topic, byte[] data) {
    if (data == null) {
      return null;
    }
    return ENCODER.encode(data); // base64 alphabet is ASCII, so these ARE the UTF-8 bytes
  }

  @Override
  public byte[] serialize(String topic, Headers headers, byte[] data) {
    return serialize(topic, data);
  }
}
