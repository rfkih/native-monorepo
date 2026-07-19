package id.co.nativeapp.events;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criterion (1): one OutboxWriter call inside a transaction yields exactly ONE outbox
 * row, and the StubRelay emits exactly one event.
 */
class OutboxWriterTest extends JdbcTestSupport {

  private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  @Test
  void oneWriteInsideTransactionYieldsExactlyOneRowAndOneEmittedEvent() {
    byte[] payload = "SaleRecorded#payload".getBytes(StandardCharsets.UTF_8);
    Instant occurredAt = Instant.parse("2026-06-13T10:15:30Z");

    UUID eventId =
        transactionTemplate.execute(
            status ->
                outboxWriter.write(
                    "sale",
                    "sale-123",
                    "SaleRecorded",
                    payload,
                    "{\"traceId\":\"abc\"}",
                    COMPANY,
                    occurredAt));

    // Exactly one outbox row written.
    assertEquals(1L, outboxRowCount(), "expected exactly one outbox row");

    // Relay emits exactly one event, carrying the written data.
    List<OutboxRecord> emitted = stubRelay.poll();
    assertEquals(1, emitted.size(), "relay must emit exactly one event");
    OutboxRecord record = emitted.get(0);
    assertEquals(eventId, record.id());
    assertEquals("sale", record.aggregateType());
    assertEquals("sale-123", record.aggregateId());
    assertEquals("SaleRecorded", record.eventType());
    assertArrayEquals(payload, record.payload());
    assertEquals("{\"traceId\":\"abc\"}", record.headers());
    // No TraceparentSupplier wired — NOOP supplier means null traceparent.
    assertNull(record.traceparent(), "no-supplier path must store null traceparent");
    assertEquals(COMPANY, record.companyId());
    assertEquals(occurredAt, record.occurredAt());
  }

  @Test
  void relayDoesNotReEmitAnAlreadyPublishedRow() {
    transactionTemplate.executeWithoutResult(
        status ->
            outboxWriter.write(
                "sale",
                "sale-1",
                "SaleRecorded",
                new byte[] {1, 2, 3},
                null,
                COMPANY,
                Instant.now()));

    assertEquals(1, stubRelay.poll().size());
    // Second poll has nothing new to publish.
    assertTrue(stubRelay.poll().isEmpty(), "published rows must not be re-emitted");
    assertEquals(1, stubRelay.emitted().size());
  }

  @Test
  void rolledBackTransactionEmitsNothing() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactionTemplate.executeWithoutResult(
                status -> {
                  outboxWriter.write(
                      "sale",
                      "sale-x",
                      "SaleRecorded",
                      new byte[] {9},
                      null,
                      COMPANY,
                      Instant.now());
                  // Force a rollback after the outbox insert.
                  throw new IllegalStateException("boom");
                }));

    // Outbox write rolled back with the business transaction; nothing to emit.
    assertEquals(0L, outboxRowCount());
    assertTrue(stubRelay.poll().isEmpty());
  }

  @Test
  void nullHeadersStoredAsNull() {
    transactionTemplate.executeWithoutResult(
        status ->
            outboxWriter.write(
                "sale", "sale-2", "SaleRecorded", new byte[] {4, 5}, null, COMPANY, Instant.now()));

    OutboxRecord record = stubRelay.poll().get(0);
    assertNull(record.headers(), "null headers must round-trip as null");
    assertNull(record.traceparent(), "no-supplier path must store null traceparent");
  }
}
