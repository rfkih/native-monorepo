package id.co.nativeapp.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent-consumer helper.
 *
 * <p>Every consumer in Native must be idempotent (CLAUDE.md rule 3; ARCHITECTURE.md
 * "dedupe by event id/key"). This store records each handled event id in the
 * {@code processed_event} table and skips duplicates, so a re-delivered event is processed
 * at most once.
 *
 * <p>The intended pattern is {@link #processOnce(UUID, Runnable)}: it claims the event id and
 * runs the handler only if the id has not been seen. For atomicity, callers should invoke
 * {@code processOnce} <em>inside the same transaction</em> as the handler's side effects, so
 * the dedupe insert and the side effects commit (or roll back) together.
 */
public class ProcessedEventStore {

    // ON CONFLICT DO NOTHING: a duplicate claim affects zero rows instead of raising a
    // unique-violation. On PostgreSQL a unique-violation would abort the entire surrounding
    // transaction ("current transaction is aborted, commands ignored until end of transaction
    // block"), poisoning the consumer's transaction — exactly the at-least-once / concurrent
    // re-delivery case this helper exists to make safe. Inspecting the affected-row count keeps
    // the caller's transaction healthy.
    private static final String INSERT_SQL =
            "INSERT INTO processed_event (event_id, processed_at) VALUES (?, ?) "
                    + "ON CONFLICT (event_id) DO NOTHING";

    private static final String EXISTS_SQL =
            "SELECT COUNT(*) FROM processed_event WHERE event_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * @return {@code true} if the given event id has already been recorded as processed.
     */
    public boolean alreadyProcessed(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        Integer count = jdbcTemplate.queryForObject(EXISTS_SQL, Integer.class, eventId);
        return count != null && count > 0;
    }

    /**
     * Runs {@code handler} exactly once for {@code eventId}. The first call for a given id
     * marks it processed and runs the handler; subsequent calls are no-ops.
     *
     * <p>The dedupe relies on the {@code processed_event} primary-key constraint to win races
     * between concurrent deliveries: the claim is an {@code ON CONFLICT DO NOTHING} insert, so
     * whichever delivery inserts the row runs the handler and a concurrent loser sees zero rows
     * affected and is treated as already-processed — without aborting its transaction.
     *
     * @return {@code true} if the handler ran (first delivery), {@code false} if skipped as a
     *     duplicate.
     */
    public boolean processOnce(UUID eventId, Runnable handler) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(handler, "handler");
        if (!claim(eventId)) {
            return false;
        }
        handler.run();
        return true;
    }

    /**
     * Attempts to record {@code eventId} as processed.
     *
     * @return {@code true} if this call inserted the row (the id was new), {@code false} if the
     *     id was already present (zero rows inserted via ON CONFLICT DO NOTHING).
     */
    private boolean claim(UUID eventId) {
        int inserted = jdbcTemplate.update(INSERT_SQL, eventId, Timestamp.from(Instant.now()));
        return inserted == 1;
    }
}
