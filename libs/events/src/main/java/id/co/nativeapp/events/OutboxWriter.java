package id.co.nativeapp.events;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes events to the transactional outbox.
 *
 * <p>The single insert runs on the {@link JdbcTemplate}'s connection, which — when this method is
 * invoked from inside a Spring-managed transaction — is the caller's own transactional connection.
 * The outbox row therefore commits atomically with the aggregate mutation: if the business
 * transaction rolls back, the event is never emitted; if it commits, the relay (Debezium / {@link
 * StubRelay}) is guaranteed to see exactly one row.
 *
 * <p>This is the ONLY sanctioned way to publish an event (CLAUDE.md rule 3 / "Never" list). It does
 * not open its own transaction; the caller owns transaction boundaries.
 *
 * <p>When a {@link TraceparentSupplier} is provided, the current W3C {@code traceparent} is
 * captured at write time and stored in the {@code traceparent} column. Debezium maps this column to
 * a Kafka header so consumers can restore the trace context and emit a child span (ADR 0010 #13 —
 * outbox→Kafka trace continuity). When the supplier is absent or returns {@code null} (no active
 * span, DB-only test modules) the column is stored as SQL NULL and the consumer starts a new root
 * span — safe, correct, just un-correlated.
 */
public class OutboxWriter {

  private static final String INSERT_SQL =
      "INSERT INTO outbox "
          + "(id, aggregate_type, aggregate_id, event_type, payload, headers, traceparent, company_id, occurred_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;
  private final TraceparentSupplier traceparentSupplier;

  /**
   * Creates an {@link OutboxWriter} without a traceparent supplier. The {@code traceparent} column
   * is always stored as SQL NULL. Preserved for backward compatibility with existing tests and with
   * callers that have no Micrometer {@code Tracer} on the classpath.
   */
  public OutboxWriter(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, TraceparentSupplier.NOOP);
  }

  /**
   * Creates an {@link OutboxWriter} that captures the current W3C {@code traceparent} from the
   * supplied {@link TraceparentSupplier} on every write.
   *
   * @param jdbcTemplate the JDBC template wired to the service's own datasource
   * @param traceparentSupplier supplies the active span's traceparent string, or {@code null}
   */
  public OutboxWriter(JdbcTemplate jdbcTemplate, TraceparentSupplier traceparentSupplier) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.traceparentSupplier = Objects.requireNonNull(traceparentSupplier, "traceparentSupplier");
  }

  /**
   * Inserts exactly one outbox row within the caller's active transaction and returns the generated
   * event id (the row's primary key).
   *
   * @param aggregateType the producing aggregate kind (e.g. {@code "sale"})
   * @param aggregateId the producing aggregate id (Kafka partition key)
   * @param eventType the event name (e.g. {@code "SaleRecorded"})
   * @param payload the serialized event body (e.g. Avro binary)
   * @param headers optional text/JSON transport metadata; {@code null} if none. Must never contain
   *     PII or secrets.
   * @param companyId the owning tenant
   * @param occurredAt when the event occurred
   * @return the event id (outbox primary key)
   */
  public UUID write(
      String aggregateType,
      String aggregateId,
      String eventType,
      byte[] payload,
      String headers,
      UUID companyId,
      Instant occurredAt) {
    UUID id = UUID.randomUUID();
    write(
        new OutboxRecord(
            id,
            aggregateType,
            aggregateId,
            eventType,
            payload,
            headers,
            traceparentSupplier.get(),
            companyId,
            occurredAt));
    return id;
  }

  /**
   * Inserts the given outbox record within the caller's active transaction. Useful when the caller
   * wants to control the event id (e.g. deterministic id derived from the aggregate).
   */
  public void write(OutboxRecord record) {
    Objects.requireNonNull(record, "record");
    jdbcTemplate.update(
        INSERT_SQL,
        ps -> {
          ps.setObject(1, record.id());
          ps.setString(2, record.aggregateType());
          ps.setString(3, record.aggregateId());
          ps.setString(4, record.eventType());
          ps.setBytes(5, record.payload());
          if (record.headers() == null) {
            ps.setNull(6, Types.VARCHAR);
          } else {
            ps.setString(6, record.headers());
          }
          if (record.traceparent() == null) {
            ps.setNull(7, Types.VARCHAR);
          } else {
            ps.setString(7, record.traceparent());
          }
          ps.setObject(8, record.companyId());
          ps.setTimestamp(9, Timestamp.from(record.occurredAt()));
        });
  }
}
