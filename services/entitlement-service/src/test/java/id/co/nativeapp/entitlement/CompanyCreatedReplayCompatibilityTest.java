package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.messaging.CompanyCreatedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The REPLAY direction of the {@code CompanyCreated} contract: a consumer running the CURRENT
 * schema must still decode a payload written by an OLDER producer.
 *
 * <p><strong>Why this is separate from the normal contract test.</strong> The compatibility check
 * everywhere else uses the 3-arg {@code AvroSerde.deserialize(bytes, writer, reader)}, which is
 * proper Avro schema resolution and trivially handles an appended optional field. Production does
 * not take that path: the listener calls the 1-arg {@code deserialize(payload, SCHEMA)}, i.e.
 * writer == reader == the consumer's current schema. Consumers are configured {@code
 * auto-offset-reset: earliest}, so a new consumer group, a group reset, or an environment rebuilt
 * without {@code __consumer_offsets} replays historical payloads straight through that path.
 *
 * <p>ADR 0070 appended {@code vertical} to {@code CompanyCreated}. This pins that the append did
 * not make pre-ADR-0070 payloads undecodable for the consumers that must replay them.
 */
class CompanyCreatedReplayCompatibilityTest {

  /** The producer schema as it stood BEFORE ADR 0070 appended {@code vertical}. */
  private static final String PRE_ADR_0070_JSON =
      """
      {
        "type": "record",
        "name": "CompanyCreated",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "company_id", "type": "string"},
          {"name": "legal_employer_id", "type": "string"},
          {"name": "base_currency", "type": "string"},
          {"name": "default_language", "type": "string"}
        ]
      }
      """;

  @Test
  void aPreAdr0070PayloadStillDecodesOnTheProductionOneArgPath() {
    Schema old = new Schema.Parser().parse(PRE_ADR_0070_JSON);
    GenericRecord produced = new GenericData.Record(old);
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    produced.put("base_currency", "IDR");
    produced.put("default_language", "id");
    byte[] wire = AvroSerde.serialize(produced);

    // EXACTLY what the listener does — writer schema is not carried on the wire.
    GenericRecord decoded = AvroSerde.deserialize(wire, CompanyCreatedSchema.schema());

    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("base_currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("default_language").toString()).isEqualTo("id");
    // The appended field is absent from the old payload; it must read as null, not blow up.
    assertThat(decoded.get("vertical")).isNull();
  }
}
