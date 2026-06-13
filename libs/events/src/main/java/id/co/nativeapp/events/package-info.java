/**
 * Reliable event-publishing plumbing for Native.
 *
 * <p>Native never publishes to Kafka directly from business logic. Instead, every
 * event is written as a row into a transactional <em>outbox</em> table inside the
 * same database transaction that mutates the aggregate (rule 3 in CLAUDE.md). A
 * change-data-capture relay (Debezium in production; {@link id.co.nativeapp.events.StubRelay}
 * in tests) tails the outbox and emits each row to Kafka exactly once the DB commits.
 *
 * <p>This package provides:
 * <ul>
 *   <li>{@link id.co.nativeapp.events.OutboxWriter} — inserts exactly one outbox row
 *       within the caller's existing transaction;</li>
 *   <li>{@link id.co.nativeapp.events.ProcessedEventStore} — an idempotent-consumer
 *       helper that records handled event ids and skips duplicates;</li>
 *   <li>{@link id.co.nativeapp.events.AvroSerde} — Avro record (de)serialization to
 *       {@code byte[]} plus a backward-compatibility checker;</li>
 *   <li>{@link id.co.nativeapp.events.StubRelay} — a test stand-in for Debezium.</li>
 * </ul>
 *
 * <p>Money is never carried as a float anywhere in an event payload; amounts travel as
 * integer minor units plus an ISO-4217 currency code. PII is never placed in headers or
 * logged.
 */
package id.co.nativeapp.events;
