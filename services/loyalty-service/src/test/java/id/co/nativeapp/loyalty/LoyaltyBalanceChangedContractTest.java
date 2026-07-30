package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyBalanceChangedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code LoyaltyBalanceChanged} PRODUCER-copy contract test (ADR 0027, no Spring context
 * needed; HR-7 triad): the schema parses with the expected shape, a {@link GenericRecord}
 * round-trips through {@code libs/events AvroSerde}, and the back-compat gate accepts an
 * added-optional-field while rejecting a new required field with no default. Also pins NO PII (rule
 * 6): {@code member_id} is an opaque UUID string field only.
 */
class LoyaltyBalanceChangedContractTest {

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = LoyaltyBalanceChangedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.loyalty.LoyaltyBalanceChanged");
    assertThat(schema.getField("member_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("points_balance").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("balance_seq").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("reason")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // NO PII (rule 6): the event never carries phone/display name.
    assertThat(schema.getField("phone")).isNull();
    assertThat(schema.getField("display_name")).isNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    UUID memberId = UUID.randomUUID();
    GenericRecord record =
        LoyaltyBalanceChangedSchema.toRecord(
            memberId, "11111111-1111-1111-1111-111111111111", 333L, 1L, "EARNED", Instant.now());

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, LoyaltyBalanceChangedSchema.schema());

    assertThat(decoded.get("member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("points_balance")).isEqualTo(333L);
    assertThat(decoded.get("balance_seq")).isEqualTo(1L);
    assertThat(decoded.get("reason").toString()).isEqualTo("EARNED");
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = LoyaltyBalanceChangedSchema.schema();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "LoyaltyBalanceChanged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "member_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "points_balance", "type": "long"},
                    {"name": "balance_seq", "type": "long"},
                    {"name": "reason", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = LoyaltyBalanceChangedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "LoyaltyBalanceChanged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "member_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "points_balance", "type": "long"},
                    {"name": "balance_seq", "type": "long"},
                    {"name": "reason", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "tier", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
