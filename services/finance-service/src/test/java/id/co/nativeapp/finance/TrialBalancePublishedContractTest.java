package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedSchema;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code TrialBalancePublished} CONSUMER contract test (P3d SEAM 2, no Spring context needed).
 *
 * <p>finance owns its own consumer copy of the schema ({@code
 * src/main/resources/avro/TrialBalancePublished.avsc}). This proves the rule-7 triad: it parses
 * from the classpath with the expected shape, round-trips a {@link GenericRecord} (including the
 * nested {@code lines} array) through {@code libs/events} {@link AvroSerde} — the exact path the
 * listener decodes off the wire and {@link TrialBalancePublishedSchema#decode} maps — and stays
 * BACKWARD-COMPATIBLE with the PRODUCER schema (embedded verbatim from docs/EVENT-CATALOG.md),
 * while a new required field with no default is rejected.
 */
class TrialBalancePublishedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "TrialBalancePublished",
        "namespace": "id.co.nativeapp.events.finance",
        "fields": [
          {"name": "company_id", "type": "string"},
          {"name": "group_id", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "base_currency", "type": "string"},
          {"name": "reconciled", "type": "boolean"},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {
            "name": "lines",
            "type": {
              "type": "array",
              "items": {
                "type": "record",
                "name": "TrialBalanceLine",
                "fields": [
                  {"name": "gl_account_code", "type": "string"},
                  {"name": "account_type", "type": "string"},
                  {"name": "posting_type", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "related_party_counterparty_id", "type": ["null","string"], "default": null},
                  {"name": "intercompany_ref", "type": ["null","string"], "default": null}
                ]
              }
            }
          }
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = TrialBalancePublishedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.finance.TrialBalancePublished");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("lines")).isNotNull();
    assertThat(schema.getField("lines").schema().getType()).isEqualTo(Schema.Type.ARRAY);
  }

  @Test
  void genericRecordWithLinesRoundTripsAndDecodes() {
    UUID member = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    GenericRecord event =
        TrialBalanceEventFixtures.trialBalancePublished(
            member,
            group,
            "2026-06",
            "IDR",
            true,
            false,
            List.of(
                TrialBalanceEventFixtures.line("4000", "REVENUE", "REVENUE", 1_500_000L, "IDR"),
                TrialBalanceEventFixtures.line("1000", "ASSET", "DEBIT", 9_000_000L, "IDR")));

    byte[] bytes = AvroSerde.serialize(event);
    TrialBalancePublishedEvent decoded = TrialBalancePublishedSchema.decode(eventId, bytes);

    assertThat(decoded.companyId()).isEqualTo(member);
    assertThat(decoded.groupId()).isEqualTo(group);
    assertThat(decoded.period()).isEqualTo("2026-06");
    assertThat(decoded.reconciled()).isTrue();
    assertThat(decoded.lines()).hasSize(2);
    assertThat(decoded.lines().get(0).glAccountCode()).isEqualTo("4000");
    assertThat(decoded.lines().get(0).amountMinor()).isEqualTo(1_500_000L);
    assertThat(decoded.lines().get(1).accountType()).isEqualTo("ASSET");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = TrialBalancePublishedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = TrialBalancePublishedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("company_id", UUID.randomUUID().toString());
    produced.put("group_id", UUID.randomUUID().toString());
    produced.put("period", "2026-06");
    produced.put("base_currency", "USD");
    produced.put("reconciled", true);
    produced.put("uses_illustrative_rules", false);
    GenericData.Record line =
        new GenericData.Record(producer.getField("lines").schema().getElementType());
    line.put("gl_account_code", "4000");
    line.put("account_type", "REVENUE");
    line.put("posting_type", "REVENUE");
    line.put("amount_minor", 42L);
    line.put("currency", "USD");
    line.put("related_party_counterparty_id", null);
    line.put("intercompany_ref", null);
    produced.put("lines", List.of(line));

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);
    assertThat(decoded.get("base_currency").toString()).isEqualTo("USD");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "TrialBalancePublished",
                  "namespace": "id.co.nativeapp.events.finance",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "group_id", "type": "string"},
                    {"name": "period", "type": "string"},
                    {"name": "base_currency", "type": "string"},
                    {"name": "reconciled", "type": "boolean"},
                    {"name": "uses_illustrative_rules", "type": "boolean"},
                    {"name": "approver_id", "type": "string"},
                    {
                      "name": "lines",
                      "type": {"type": "array", "items": {
                        "type": "record", "name": "TrialBalanceLine", "fields": [
                          {"name": "gl_account_code", "type": "string"},
                          {"name": "account_type", "type": "string"},
                          {"name": "posting_type", "type": "string"},
                          {"name": "amount_minor", "type": "long"},
                          {"name": "currency", "type": "string"},
                          {"name": "related_party_counterparty_id", "type": ["null","string"], "default": null},
                          {"name": "intercompany_ref", "type": ["null","string"], "default": null}
                        ]}}
                    }
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
