package id.co.nativeapp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion (#13 — outbox→Kafka trace continuity, producer side):
 *
 * <ul>
 *   <li>(a) When a {@link TraceparentSupplier} returns a non-null value, {@link OutboxWriter}
 *       stores that value in the {@code traceparent} column and the {@link StubRelay} reads it back
 *       unchanged.
 *   <li>(b) When the supplier returns {@code null} (no active span / NOOP), the column is stored as
 *       SQL NULL and the relay reads back {@code null} — so a consumer that receives no header
 *       simply starts a new root span rather than failing.
 * </ul>
 *
 * <p>No Micrometer {@code Tracer} is on this classpath — {@code MicrometerTraceparentSupplier} is
 * used only as a compile-visible reference; the tests exercise the interface via a plain lambda,
 * confirming that {@code libs/events} does NOT force Micrometer onto DB-only test modules.
 */
class OutboxWriterTraceparentTest extends JdbcTestSupport {

  private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final String SAMPLE_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void traceparentStoredWhenSupplierReturnsValue() {
    // A supplier that returns a fixed traceparent (simulates a span in scope).
    OutboxWriter writerWithTrace = new OutboxWriter(jdbcTemplate, () -> SAMPLE_TRACEPARENT);

    transactionTemplate.executeWithoutResult(
        status ->
            writerWithTrace.write(
                "sale", "sale-tp-1", "SaleRecorded", new byte[] {1}, null, COMPANY, Instant.now()));

    OutboxRecord record = stubRelay.poll().get(0);
    assertEquals(
        SAMPLE_TRACEPARENT,
        record.traceparent(),
        "traceparent must be stored when the supplier returns a non-null value");
  }

  @Test
  void traceparentIsNullWhenSupplierReturnsNull() {
    // A supplier that returns null (simulates no span in scope).
    OutboxWriter writerNoTrace = new OutboxWriter(jdbcTemplate, () -> null);

    transactionTemplate.executeWithoutResult(
        status ->
            writerNoTrace.write(
                "sale", "sale-tp-2", "SaleRecorded", new byte[] {2}, null, COMPANY, Instant.now()));

    OutboxRecord record = stubRelay.poll().get(0);
    assertNull(record.traceparent(), "traceparent must be SQL NULL when the supplier returns null");
  }

  @Test
  void noopSupplierYieldsNullTraceparent() {
    // The default NOOP supplier (the no-args constructor).
    OutboxWriter defaultWriter = new OutboxWriter(jdbcTemplate);

    transactionTemplate.executeWithoutResult(
        status ->
            defaultWriter.write(
                "sale", "sale-tp-3", "SaleRecorded", new byte[] {3}, null, COMPANY, Instant.now()));

    OutboxRecord record = stubRelay.poll().get(0);
    assertNull(
        record.traceparent(),
        "the default NOOP supplier must store null traceparent (backward-compatible)");
  }

  @Test
  void directOutboxRecordWritePreservesTraceparent() {
    // Writing via the OutboxRecord overload preserves the traceparent field.
    OutboxRecord outboxRecord =
        new OutboxRecord(
            UUID.randomUUID(),
            "sale",
            "sale-tp-4",
            "SaleRecorded",
            new byte[] {4},
            null,
            SAMPLE_TRACEPARENT,
            COMPANY,
            Instant.now());

    transactionTemplate.executeWithoutResult(status -> outboxWriter.write(outboxRecord));

    OutboxRecord emitted = stubRelay.poll().get(0);
    assertEquals(
        SAMPLE_TRACEPARENT,
        emitted.traceparent(),
        "traceparent from an explicit OutboxRecord must round-trip unchanged");
  }
}
