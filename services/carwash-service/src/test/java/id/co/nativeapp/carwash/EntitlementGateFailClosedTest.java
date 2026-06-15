package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The entitlement gate FAILS CLOSED when its entitlement state cannot be resolved — it must NOT
 * fall open and silently allow a wash. This mirrors the fail-closed posture restaurant/finance
 * prove for a tenant scope that cannot be resolved: when the dependency the gate relies on is
 * unavailable, the safe answer is to DENY.
 *
 * <p><strong>The simulated outage.</strong> The carwash entitlement gate consults the shared {@code
 * libs/entitlement-check} checker, whose first hop is a Redis cache lookup. This test stands up its
 * OWN dedicated, stoppable Redis (distinct from the shared singleton in {@link
 * KafkaPostgresRedisTestBase}, so stopping it cannot disturb the other contexts), wires the service
 * at it, then:
 *
 * <ol>
 *   <li>with Redis UP, grants the carwash module and proves a wash WOULD succeed (the company is
 *       genuinely entitled — so a denial after the outage is the gate failing closed, not a missing
 *       grant);
 *   <li>STOPS Redis mid-test, so the very next cache lookup throws a connection failure;
 *   <li>asserts a record-wash for that same entitled tenant now FAILS (the connection error
 *       propagates out of the gate BEFORE any side effect) rather than falling open.
 * </ol>
 *
 * <p>Fail-closed is then proven by the absence of side effects: over the admin/BYPASSRLS connection
 * there is still exactly the ONE wash from the up-front success and exactly its ONE {@code
 * SaleRecorded} — the outage attempt wrote no new row and emitted no new event.
 */
@SpringBootTest
class EntitlementGateFailClosedTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CARWASH = "carwash";

  // A DEDICATED Redis for this class only — NOT the shared KafkaPostgresRedisTestBase singleton —
  // so it can be stopped mid-test to simulate the outage without breaking any other context. Kafka
  // is not needed here: the projection is seeded directly via EntitlementProjectionService, not
  // over
  // the wire, so this test extends the Postgres base (no broker) and wires only its own Redis.
  @SuppressWarnings("resource") // explicitly stopped in the test; otherwise reaped by Ryuk at exit
  static final GenericContainer<?> DEDICATED_REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    DEDICATED_REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", DEDICATED_REDIS::getHost);
    registry.add("spring.data.redis.port", () -> DEDICATED_REDIS.getMappedPort(6379));
  }

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void gateDeniesAndWritesNothingWhenTheEntitlementDependencyIsDown() throws Exception {
    // (1) Redis UP: grant carwash and prove the company is genuinely entitled — a wash succeeds.
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, CARWASH, true));

    UUID createdWashId =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> washService.recordWash(command("ok-1")))
            .wash()
            .getId();
    assertThat(createdWashId).isNotNull();
    assertThat(washRowCountAsAdmin()).isEqualTo(1L);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);

    // (2) The entitlement dependency goes down: stop Redis so the next cache lookup cannot answer.
    DEDICATED_REDIS.stop();

    // (3) The gate must FAIL CLOSED — the connection failure propagates out of recordWash and the
    // wash is denied. It must NOT silently fall open and allow the still-"entitled" tenant through.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR_A, () -> washService.recordWash(command("outage-1"))))
        .as("the gate denies (throws) when entitlement state cannot be resolved, never falls open")
        .isInstanceOf(Exception.class);

    // Fail-closed proof: no NEW side effects. Still exactly the one wash + one SaleRecorded from
    // the
    // pre-outage success; the outage attempt wrote no row and emitted no event.
    assertThat(washRowCountAsAdmin()).isEqualTo(1L);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);
  }

  private static RecordWashCommand command(String idempotencyKey) {
    return new RecordWashCommand(
        OUTLET,
        "bay-1",
        5_000_00L,
        "IDR",
        null,
        null,
        Instant.parse("2026-06-14T08:30:00Z"),
        idempotencyKey);
  }

  private long washRowCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM wash");
  }

  private long saleRecordedCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM outbox WHERE event_type = 'SaleRecorded'");
  }

  private long countAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
