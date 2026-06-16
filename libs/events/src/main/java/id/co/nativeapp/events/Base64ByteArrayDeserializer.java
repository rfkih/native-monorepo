package id.co.nativeapp.events;

import java.util.Base64;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Kafka value deserializer that reverses {@link Base64ByteArraySerializer}: it base64-decodes the
 * record value (the UTF-8 bytes of base64 text that Debezium / the test producers / the StubRelay
 * put on the wire) back into the raw Avro {@code byte[]} the {@code @KafkaListener} receives.
 *
 * <p>The base64 layer is purely a Kafka <em>transport</em> encoding (see {@link
 * Base64ByteArraySerializer} for why the {@code bytea} outbox payload must be base64'd rather than
 * shipped verbatim). Once decoded, the listener does the Avro decode itself with {@code AvroSerde}
 * against its own copy of the schema — there is NO Confluent Schema Registry serde and {@code
 * AvroSerde}'s raw-bytes contract is unchanged: the {@code byte[]} this returns is byte-for-byte
 * the payload the producer serialized.
 *
 * @see Base64ByteArraySerializer
 */
public final class Base64ByteArrayDeserializer implements Deserializer<byte[]> {

  private static final Base64.Decoder DECODER = Base64.getDecoder();

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {
    // No configuration: the encoding is fixed (RFC 4648 base64, the same alphabet Debezium emits).
  }

  /**
   * Base64-decodes {@code data} (the UTF-8 bytes of the base64 text on the wire) back to the raw
   * Avro bytes. A {@code null} value decodes to {@code null} (the outbox never produces one).
   */
  @Override
  public byte[] deserialize(String topic, byte[] data) {
    if (data == null) {
      return null;
    }
    return DECODER.decode(data);
  }

  @Override
  public byte[] deserialize(String topic, Headers headers, byte[] data) {
    return deserialize(topic, data);
  }
}
