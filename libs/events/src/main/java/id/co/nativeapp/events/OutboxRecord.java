package id.co.nativeapp.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable view of one row in the transactional {@code outbox} table.
 *
 * <p>The relay reads these and emits them to Kafka; the {@link OutboxWriter} produces them. {@code
 * payload} is the serialized event body (typically Avro binary from {@link AvroSerde}). {@code
 * headers} is an optional text/JSON blob for transport metadata — it must NEVER carry PII or
 * secrets. {@code companyId} keeps every event tenant-scoped (CLAUDE.md rule 5).
 *
 * @param id outbox row primary key; also serves as the event id for idempotency
 * @param aggregateType the kind of aggregate that produced the event (e.g. {@code "sale"})
 * @param aggregateId the producing aggregate's id (the Kafka partition key in production)
 * @param eventType the event name (e.g. {@code "SaleRecorded"})
 * @param payload the serialized event body; defensively copied
 * @param headers optional transport metadata as text/JSON; may be {@code null}
 * @param companyId the owning tenant
 * @param occurredAt when the event occurred (stored as {@code timestamptz})
 */
public record OutboxRecord(
    UUID id,
    String aggregateType,
    String aggregateId,
    String eventType,
    byte[] payload,
    String headers,
    UUID companyId,
    Instant occurredAt) {

  public OutboxRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    payload = payload.clone();
  }

  /**
   * @return a defensive copy of the payload bytes.
   */
  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
