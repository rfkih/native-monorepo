package id.co.nativeapp.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * A test stand-in for the production Debezium CDC relay.
 *
 * <p>Debezium tails the {@code outbox} table's transaction log and publishes each new row to Kafka.
 * This stub instead polls the table for rows not yet published (where {@code published_at IS
 * NULL}), "emits" them into an in-memory list, and stamps {@code published_at} so they are not
 * re-emitted. That lets tests assert the end-to-end guarantee — one {@link OutboxWriter} write
 * inside a committed transaction yields exactly one emitted event — without standing up Kafka.
 *
 * <p>Not for production use; it exists only to exercise the plumbing.
 */
public class StubRelay {

  private static final String SELECT_UNPUBLISHED_SQL =
      "SELECT id, aggregate_type, aggregate_id, event_type, payload, headers, traceparent, company_id, occurred_at "
          + "FROM outbox WHERE published_at IS NULL ORDER BY occurred_at, id";

  private static final String MARK_PUBLISHED_SQL =
      "UPDATE outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?";

  private static final RowMapper<OutboxRecord> ROW_MAPPER =
      (rs, rowNum) ->
          new OutboxRecord(
              rs.getObject("id", UUID.class),
              rs.getString("aggregate_type"),
              rs.getString("aggregate_id"),
              rs.getString("event_type"),
              rs.getBytes("payload"),
              rs.getString("headers"),
              rs.getString("traceparent"),
              rs.getObject("company_id", UUID.class),
              rs.getTimestamp("occurred_at").toInstant());

  private final JdbcTemplate jdbcTemplate;
  private final List<OutboxRecord> emitted = new ArrayList<>();

  public StubRelay(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  /**
   * Reads every unpublished outbox row, appends it to the in-memory emitted list, marks it
   * published, and returns the rows emitted by this poll.
   *
   * @return the records emitted during this invocation (empty if there was nothing to do)
   */
  public List<OutboxRecord> poll() {
    List<OutboxRecord> unpublished = jdbcTemplate.query(SELECT_UNPUBLISHED_SQL, ROW_MAPPER);
    for (OutboxRecord record : unpublished) {
      emitted.add(record);
      jdbcTemplate.update(MARK_PUBLISHED_SQL, record.id());
    }
    return List.copyOf(unpublished);
  }

  /**
   * @return an unmodifiable view of every record emitted so far across all polls.
   */
  public List<OutboxRecord> emitted() {
    return Collections.unmodifiableList(emitted);
  }
}
