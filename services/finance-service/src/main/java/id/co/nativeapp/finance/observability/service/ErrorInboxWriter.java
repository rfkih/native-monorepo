package id.co.nativeapp.finance.observability.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a production error to the {@code error_log} ops table with fingerprint-based dedup (ADR
 * 0005).
 *
 * <p>The upsert runs in a {@link TransactionTemplate} configured with {@code
 * PROPAGATION_REQUIRES_NEW} so it commits even when the calling business transaction has rolled
 * back — otherwise a DLT'd event would never reach the inbox because the business tx that surfaced
 * the error was already aborted.
 *
 * <p>This class uses plain {@link JdbcTemplate}, NOT a JPA {@code @Entity} or Spring Data
 * repository. {@code error_log} is an ops / infrastructure table deliberately exempt from the
 * {@code Auditable} and native-query/projection ArchUnit rules — see ADR 0005 and the V14 migration
 * header for the full rationale.
 *
 * <p>All failures are swallowed: if recording itself fails (e.g. the DB is unreachable) the caller
 * must not see an exception — ops infrastructure must never break the business path.
 */
@Component
public class ErrorInboxWriter {

  private static final Logger log = LoggerFactory.getLogger(ErrorInboxWriter.class);

  private static final String UPSERT_SQL =
      "INSERT INTO error_log"
          + " (id, fingerprint, exception_class, redacted_message, source,"
          + "  company_id, trace_id, occurrence_count, first_seen, last_seen)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)"
          + " ON CONFLICT (fingerprint) DO UPDATE"
          + " SET occurrence_count = error_log.occurrence_count + 1,"
          + "     last_seen = excluded.last_seen,"
          + "     redacted_message = excluded.redacted_message"
          + " RETURNING occurrence_count";

  private final ErrorMessageRedactor redactor;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final Clock clock;

  public ErrorInboxWriter(
      ErrorMessageRedactor redactor,
      JdbcTemplate jdbcTemplate,
      TransactionTemplate errorInboxTransactionTemplate,
      Clock clock) {
    this.redactor = redactor;
    this.jdbc = jdbcTemplate;
    this.requiresNew = errorInboxTransactionTemplate;
    this.clock = clock;
  }

  /**
   * Records the error and returns the post-upsert {@code occurrence_count}.
   *
   * <p>The raw exception is first unwrapped to its most specific cause (peeling Spring Kafka /
   * {@link java.util.concurrent.ExecutionException} wrappers). The exception message is redacted
   * before storage. Two errors differing only in embedded numbers will share a fingerprint and
   * deduplicate to a single row.
   *
   * @param ex the throwable to record; must not be {@code null}
   * @param source the origin label, e.g. {@code "kafka:SaleRecorded"}
   * @param companyId the tenant, or {@code null} when not resolvable (e.g. deserialisation failure)
   * @param traceId the W3C trace-id from MDC, or {@code null}
   * @return a {@link Recorded} carrying the post-upsert {@code occurrence_count}, the dedup {@code
   *     fingerprint}, and the PII-{@code redactedMessage}; {@link Recorded#failed()} (count {@code
   *     0}, null fingerprint) if recording failed. The redacted message is returned so the alert
   *     egress path can use it — the raw message must NEVER leave this service (HR-6 / ADR 0005).
   */
  public Recorded record(Throwable ex, String source, String companyId, String traceId) {
    try {
      Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
      String exceptionClass = cause.getClass().getName();
      String rawMessage = cause.getMessage();
      String redactedMessage = redactor.redact(rawMessage);
      String fingerprint = fingerprint(exceptionClass, rawMessage, source);

      // Convert to java.sql.Timestamp: the PostgreSQL JDBC driver cannot infer
      // the SQL type for java.time.Instant directly (it requires an explicit Types hint).
      // Timestamp is the canonical JDBC mapping for TIMESTAMPTZ / TIMESTAMP columns.
      Timestamp now = Timestamp.from(Instant.now(clock));
      Long count =
          requiresNew.execute(
              status ->
                  jdbc.queryForObject(
                      UPSERT_SQL,
                      Long.class,
                      UUID.randomUUID(),
                      fingerprint,
                      exceptionClass,
                      redactedMessage,
                      source,
                      companyId,
                      traceId,
                      now,
                      now));

      return new Recorded(count != null ? count : 0L, fingerprint, redactedMessage);
    } catch (Exception e) {
      log.warn(
          "error-inbox: failed to record error (source={}, exClass={}); continuing",
          source,
          ex.getClass().getName(),
          e);
      return Recorded.failed();
    }
  }

  /**
   * The outcome of an inbox upsert: the post-upsert {@code occurrenceCount}, the dedup {@code
   * fingerprint} (the same value stored in {@code error_log.fingerprint}, so an alert can be
   * correlated back to the row), and the already-PII-redacted {@code redactedMessage} (the value
   * stored in {@code error_log.redacted_message}). The recorder MUST use {@code redactedMessage}
   * for any alert egress — never the raw exception message (HR-6 / ADR 0005).
   */
  public record Recorded(long occurrenceCount, String fingerprint, String redactedMessage) {

    /** The sentinel returned when recording failed: count {@code 0} suppresses any alert. */
    static Recorded failed() {
      return new Recorded(0L, null, "");
    }
  }

  /**
   * Computes the dedup fingerprint as SHA-256 hex of {@code exceptionClass + "|" +
   * fingerprintNormalize(message) + "|" + source}.
   */
  private String fingerprint(String exceptionClass, String rawMessage, String source) {
    String normalized = redactor.fingerprintNormalize(rawMessage);
    String input = exceptionClass + "|" + normalized + "|" + source;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the Java spec; this can never happen in practice.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
