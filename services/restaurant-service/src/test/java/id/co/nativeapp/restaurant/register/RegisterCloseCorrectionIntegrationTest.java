package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.domain.RegisterCloseCorrectionNotAllowedException;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotClosedException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.CorrectCloseRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.messaging.RegisterSessionClosedSchema;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the manager/owner CASH-count CORRECTION of a
 * CLOSED register session (ADR 0064): the session is amended in place (counted + over/short + a
 * bumped close_seq), and a SECOND {@code RegisterSessionClosed} outbox event is emitted MARKED as
 * superseding the original close (so finance reverses the prior variance and posts the corrected
 * one). Also: an OPEN session cannot be corrected (409), and correcting to the recorded value is a
 * no-op (idempotent).
 */
@SpringBootTest
class RegisterCloseCorrectionIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "manager-correct@example.co.id";
  private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

  @Autowired private RegisterSessionService service;

  private static <T> T asTenant(Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void correctingACashMiscountAmendsTheSessionAndEmitsASupersedingEvent() throws Exception {
    UUID outlet = UUID.fromString("dddddddd-1111-1111-1111-111111111111");

    // Open, ring a 100k CASH sale, then CLOSE with the WRONG count 90k → expected 100k, short 10k.
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    seedCashSale(outlet, Instant.now(), 100_000L);
    RegisterSessionResponse closed =
        asTenant(
            () -> service.close(sessionId, new CloseSessionRequest(90_000L), "close:" + sessionId));
    assertThat(closed.expectedCashMinor()).isEqualTo(100_000L);
    assertThat(closed.countedCashMinor()).isEqualTo(90_000L);
    assertThat(closed.overShortMinor()).isEqualTo(-10_000L);

    // Manager corrects: the real drawer was 100k (a miscount) → over/short becomes 0.
    RegisterSessionResponse corrected =
        asTenant(
            () ->
                service.correctClose(
                    sessionId, new CorrectCloseRequest(100_000L, "cashier miscounted the drawer")));

    // Session amended in place, still CLOSED; expected is unchanged (window historical).
    assertThat(corrected.status()).isEqualTo("CLOSED");
    assertThat(corrected.expectedCashMinor()).isEqualTo(100_000L);
    assertThat(corrected.countedCashMinor()).isEqualTo(100_000L);
    assertThat(corrected.overShortMinor()).isZero();

    // The Z-report reads the corrected figures.
    RegisterSummaryResponse summary = asTenant(() -> service.summarize(sessionId));
    assertThat(summary.countedCashMinor()).isEqualTo(100_000L);
    assertThat(summary.overShortMinor()).isZero();

    // close_seq bumped to 2; close_event_id re-pointed at the correction event.
    long[] seqHolder = new long[1];
    UUID closeEventIdOnRow;
    try (Connection admin = admin()) {
      try (PreparedStatement ps =
          admin.prepareStatement(
              "SELECT close_seq, close_event_id FROM cash_register_session WHERE id = ?")) {
        ps.setObject(1, sessionId);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
          seqHolder[0] = rs.getInt("close_seq");
          closeEventIdOnRow = (UUID) rs.getObject("close_event_id");
        }
      }
    }
    assertThat(seqHolder[0]).isEqualTo(2L);

    // Two RegisterSessionClosed events: the original (supersedes null) + the correction (supersedes
    // = the original's outbox id). Identify by content, not order — both carry the same
    // occurred_at.
    List<OutboxEvent> events = readRegisterClosedEvents(sessionId);
    assertThat(events).hasSize(2);
    OutboxEvent original =
        events.stream()
            .filter(e -> e.record.get("supersedes_event_id") == null)
            .findFirst()
            .orElseThrow();
    OutboxEvent correction =
        events.stream()
            .filter(e -> e.record.get("supersedes_event_id") != null)
            .findFirst()
            .orElseThrow();

    // Original close snapshot = the WRONG count.
    assertThat((long) original.record.get("counted_cash_minor")).isEqualTo(90_000L);
    assertThat((long) original.record.get("over_short_minor")).isEqualTo(-10_000L);
    assertThat((int) original.record.get("close_seq")).isEqualTo(1);

    // Correction supersedes the original's event id, seq 2, corrected figures + reason.
    assertThat(correction.record.get("supersedes_event_id").toString())
        .isEqualTo(original.outboxId.toString());
    assertThat((int) correction.record.get("close_seq")).isEqualTo(2);
    assertThat((long) correction.record.get("counted_cash_minor")).isEqualTo(100_000L);
    assertThat((long) correction.record.get("over_short_minor")).isZero();
    assertThat(correction.record.get("reason").toString())
        .isEqualTo("cashier miscounted the drawer");
    // The session row now points at the correction event (a further correction would supersede it).
    assertThat(closeEventIdOnRow).isEqualTo(correction.outboxId);
  }

  @Test
  void correctingAnOpenSessionIsRejected() throws Exception {
    UUID outlet = UUID.fromString("dddddddd-2222-2222-2222-222222222222");
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    assertThatThrownBy(
            () ->
                asTenant(
                    () -> service.correctClose(sessionId, new CorrectCloseRequest(10_000L, "x"))))
        .isInstanceOf(RegisterSessionNotClosedException.class);
  }

  @Test
  void correctingToTheRecordedValueIsANoOp() throws Exception {
    UUID outlet = UUID.fromString("dddddddd-3333-3333-3333-333333333333");
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    asTenant(() -> service.close(sessionId, new CloseSessionRequest(0L), "close:" + sessionId));

    // Correcting to the value already recorded (0) changes nothing and emits no new event.
    asTenant(() -> service.correctClose(sessionId, new CorrectCloseRequest(0L, "no change")));

    assertThat(readRegisterClosedEvents(sessionId)).hasSize(1); // only the original close event
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT close_seq FROM cash_register_session WHERE id = ?")) {
      ps.setObject(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("close_seq")).isEqualTo(1); // never bumped
      }
    }
  }

  @Test
  void correctingACloseOlderThanTheRecencyWindowIsRejected() throws Exception {
    UUID outlet = UUID.fromString("dddddddd-4444-4444-4444-444444444444");
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    asTenant(() -> service.close(sessionId, new CloseSessionRequest(0L), "close:" + sessionId));

    // Backdate the close to 90 days ago (past the 62-day recency bound) — recent/unsealed only.
    setClosedAt(sessionId, Instant.now().minus(90, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.correctClose(sessionId, new CorrectCloseRequest(10_000L, "late"))))
        .isInstanceOf(RegisterCloseCorrectionNotAllowedException.class);
  }

  @Test
  void correctingACloseThatPredatesTheFeatureIsRejected() throws Exception {
    UUID outlet = UUID.fromString("dddddddd-5555-5555-5555-555555555555");
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    asTenant(() -> service.close(sessionId, new CloseSessionRequest(0L), "close:" + sessionId));

    // Simulate a pre-ADR-0064 close: no reversible event id → finance has nothing to reverse.
    clearCloseEventId(sessionId);

    assertThatThrownBy(
            () ->
                asTenant(
                    () -> service.correctClose(sessionId, new CorrectCloseRequest(10_000L, "old"))))
        .isInstanceOf(RegisterCloseCorrectionNotAllowedException.class);
  }

  // ---- outbox / db helpers -------------------------------------------------------------------

  private void setClosedAt(UUID sessionId, Instant closedAt) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("UPDATE cash_register_session SET closed_at = ? WHERE id = ?")) {
      ps.setObject(1, OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC));
      ps.setObject(2, sessionId);
      ps.executeUpdate();
    }
  }

  private void clearCloseEventId(UUID sessionId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE cash_register_session SET close_event_id = NULL WHERE id = ?")) {
      ps.setObject(1, sessionId);
      ps.executeUpdate();
    }
  }

  private record OutboxEvent(UUID outboxId, GenericRecord record) {}

  private static Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** All RegisterSessionClosed outbox events for a session, deserialized (admin bypasses RLS). */
  private List<OutboxEvent> readRegisterClosedEvents(UUID sessionId) throws Exception {
    List<OutboxEvent> events = new ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id, payload FROM outbox WHERE event_type = 'RegisterSessionClosed'"
                    + " AND aggregate_id = ? ORDER BY occurred_at")) {
      ps.setString(1, sessionId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          GenericRecord record =
              AvroSerde.deserialize(rs.getBytes("payload"), RegisterSessionClosedSchema.schema());
          events.add(new OutboxEvent((UUID) rs.getObject("id"), record));
        }
      }
    }
    return events;
  }

  /** Seeds a raw CASH sale (bypassing SaleWriter) pinned to an explicit {@code occurred_at}. */
  private void seedCashSale(UUID outlet, Instant occurredAt, long amountMinor) throws Exception {
    try (Connection c = admin();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO sale (id, business_id, amount_minor, currency, occurred_at,"
                    + " idempotency_key, tender_type, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'IDR', ?, ?, 'CASH', now(), 'test', now(), 'test', 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, outlet);
      ps.setLong(3, amountMinor);
      ps.setObject(4, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
      ps.setString(5, UUID.randomUUID().toString());
      ps.setString(6, TENANT);
      ps.executeUpdate();
    }
  }
}
