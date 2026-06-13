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
 * <p>The single insert runs on the {@link JdbcTemplate}'s connection, which — when this
 * method is invoked from inside a Spring-managed transaction — is the caller's own
 * transactional connection. The outbox row therefore commits atomically with the aggregate
 * mutation: if the business transaction rolls back, the event is never emitted; if it
 * commits, the relay (Debezium / {@link StubRelay}) is guaranteed to see exactly one row.
 *
 * <p>This is the ONLY sanctioned way to publish an event (CLAUDE.md rule 3 / "Never" list).
 * It does not open its own transaction; the caller owns transaction boundaries.
 */
public class OutboxWriter {

    private static final String INSERT_SQL =
            "INSERT INTO outbox "
                    + "(id, aggregate_type, aggregate_id, event_type, payload, headers, company_id, occurred_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public OutboxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Inserts exactly one outbox row within the caller's active transaction and returns the
     * generated event id (the row's primary key).
     *
     * @param aggregateType the producing aggregate kind (e.g. {@code "sale"})
     * @param aggregateId   the producing aggregate id (Kafka partition key)
     * @param eventType     the event name (e.g. {@code "SaleRecorded"})
     * @param payload       the serialized event body (e.g. Avro binary)
     * @param headers       optional text/JSON transport metadata; {@code null} if none. Must
     *                      never contain PII or secrets.
     * @param companyId     the owning tenant
     * @param occurredAt    when the event occurred
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
        write(new OutboxRecord(
                id, aggregateType, aggregateId, eventType, payload, headers, companyId, occurredAt));
        return id;
    }

    /**
     * Inserts the given outbox record within the caller's active transaction. Useful when the
     * caller wants to control the event id (e.g. deterministic id derived from the aggregate).
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
                    ps.setObject(7, record.companyId());
                    ps.setTimestamp(8, Timestamp.from(record.occurredAt()));
                });
    }
}
