package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.RecordWashResult;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The headline feature under CONCURRENCY (ENGINEERING-STANDARDS §3.2), mirroring
 * restaurant-service's {@code RecordSaleConcurrencyTest}: two threads record a wash at the same
 * time with the SAME {@code idempotency_key} in the SAME tenant scope, the company being entitled
 * to the carwash module.
 *
 * <p>Proves the concurrency-safe idempotency contract of {@link WashService} — the
 * unique-constraint-loser conflict-recovery branch ({@code DataIntegrityViolationException} ->
 * separate-transaction re-read):
 *
 * <ul>
 *   <li>exactly ONE {@code wash} row exists (counted over the admin/BYPASSRLS connection, since
 *       {@code wash} is {@code FORCE} RLS);
 *   <li>exactly ONE {@code SaleRecorded} outbox row exists (the conflict path writes no second
 *       event);
 *   <li>BOTH callers receive a successful idempotent result — no exception, no 500 — with exactly
 *       one {@code created=true} and one {@code created=false}, both resolving to the same wash id.
 * </ul>
 *
 * <p>The loser of the unique-constraint race trips a {@code DataIntegrityViolationException}, which
 * aborts its create transaction; the service recovers it with a separate-transaction re-read and
 * returns the winner's wash with {@code created=false}. Extends {@link KafkaPostgresRedisTestBase}
 * (not just Postgres) because the entitlement gate consults the Redis-cached checker before the
 * write.
 */
@SpringBootTest
class RecordWashConcurrencyTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void concurrentSameKeyYieldsOneWashOneEventAndTwoSuccessfulResults() throws Exception {
    // The company is entitled so both racers pass the gate and reach the INSERT race (the point of
    // this test); the entitled? answer is seeded through the same projected-event consumer path
    // production uses.
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, CARWASH, true));

    String idempotencyKey = "race-key-123";
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    RecordWashCommand command =
        new RecordWashCommand(
            OUTLET, "bay-1", 7_500_00L, "IDR", "wax", 2_500_00L, occurredAt, idempotencyKey);

    // A barrier so both threads enter recordWash as close to simultaneously as possible, maximizing
    // the chance both miss the idempotency short-circuit and race the INSERT (the loser must then
    // be
    // recovered, not 500).
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<RecordWashResult> attempt =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return washService.recordWash(command);
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<RecordWashResult> f1 = pool.submit(attempt);
      Future<RecordWashResult> f2 = pool.submit(attempt);

      // Neither call throws (no 500) — both return a successful idempotent result.
      RecordWashResult r1 = f1.get();
      RecordWashResult r2 = f2.get();

      // Exactly one created=true and one created=false, both the same wash id.
      assertThat(r1.created() ^ r2.created())
          .as("exactly one caller created the wash, the other was idempotent")
          .isTrue();
      assertThat(r1.wash().getId())
          .as("both callers resolve to the same single wash")
          .isEqualTo(r2.wash().getId());
    } finally {
      pool.shutdownNow();
    }

    // Exactly ONE wash row, counted over the admin (BYPASSRLS) connection.
    assertThat(washRowCountAsAdmin()).isEqualTo(1L);

    // Exactly ONE SaleRecorded outbox row — the conflict path emitted nothing.
    Long outboxCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'", Long.class);
    assertThat(outboxCount).isEqualTo(1L);
  }

  private long washRowCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM wash")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
