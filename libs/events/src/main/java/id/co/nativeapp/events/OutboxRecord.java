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
 * secrets. {@code traceparent} is the W3C {@code traceparent} string captured at write time so
 * Debezium can map it to a Kafka header and the consumer can restore the trace context (ADR 0010
 * #13 — outbox→Kafka trace continuity). {@code companyId} keeps every event tenant-scoped (CLAUDE.md
 * rule 5).
 *
 * @param id outbox row primary key; also serves as the event id for idempotency
 * @param aggregateType the kind of aggregate that produced the event (e.g. {@code "sale"})
 * @param aggregateId the producing aggregate's id (the Kafka partition key in production)
 * @param eventType the event name (e.g. {@code "SaleRecorded"})
 * @param payload the serialized event body; defensively copied
 * @param headers optional transport metadata as text/JSON; may be {@code null}
 * @param traceparent optional W3C traceparent string ({@code 00-<traceId>-<spanId>-<flags>}) of
 *     the span active when the event was written; {@code null} when no span was in scope or when
 *     tracing is not wired (DB-only test modules)
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
    String traceparent,
    UUID companyId,
    Instant occurredAt) {

  public OutboxRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    // traceparent is intentionally nullable — no requireNonNull
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
