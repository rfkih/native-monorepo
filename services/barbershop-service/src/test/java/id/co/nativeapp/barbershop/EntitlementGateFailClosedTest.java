package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
 * fall open and silently allow a ticket checkout. Ported from carwash-service's {@code
 * EntitlementGateFailClosedTest} (ADR 0024), adapted to the ONLY revenue path this service has: a
 * ticket checkout (barbershop-service has no legacy record-wash analog).
 *
 * <p><strong>The simulated outage.</strong> The barbershop entitlement gate consults the shared
 * {@code libs/entitlement-check} checker, whose first hop is a Redis cache lookup. This test stands
 * up its OWN dedicated, stoppable Redis (distinct from the shared singleton in {@link
 * KafkaPostgresRedisTestBase}, so stopping it cannot disturb the other contexts), wires the service
 * at it, then:
 *
 * <ol>
 *   <li>with Redis UP, grants the barbershop module (directly, via {@link
 *       EntitlementProjectionService} — no Kafka needed for this synchronous seed), creates a
 *       service + staff profile, and proves a CASH ticket checkout succeeds (the company is
 *       genuinely entitled — so a denial after the outage is the gate failing closed, not a missing
 *       grant);
 *   <li>STOPS Redis mid-test, so the very next cache lookup throws a connection failure;
 *   <li>asserts a checkout for that same entitled tenant now FAILS (the connection error propagates
 *       out of the gate BEFORE any side effect) rather than falling open.
 * </ol>
 *
 * <p>Fail-closed is then proven by the absence of side effects: over the admin/BYPASSRLS connection
 * there is still exactly the ONE ticket from the up-front success and exactly its ONE {@code
 * SaleRecorded} — the outage attempt wrote no new row and emitted no new event.
 */
@SpringBootTest
class EntitlementGateFailClosedTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String BARBERSHOP = "barbershop";

  // A DEDICATED Redis for this class only — NOT the shared KafkaPostgresRedisTestBase singleton —
  // so it can be stopped mid-test to simulate the outage without breaking any other context. Kafka
  // is not needed here: the projection is seeded directly via EntitlementProjectionService, not
  // over the wire, so this test extends the Postgres base (no broker) and wires only its own
  // Redis.
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

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void gateDeniesAndWritesNothingWhenTheEntitlementDependencyIsDown() throws Exception {
    // (1) Redis UP: grant barbershop, seed catalog + staff, and prove the company is genuinely
    // entitled — a CASH checkout succeeds.
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, BARBERSHOP, true));

    CatalogItemResponse service =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Haircut", null, 50_000_00L, "IDR", null)));
    StaffProfileResponse barber =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi", null, true)));

    UUID createdTicketId =
        TenantContext.callAs(
                TENANT_A, ACTOR_A, () -> ticketService.checkout(checkoutRequest(service, barber, "ok-1")))
            .ticket()
            .ticketId();
    assertThat(createdTicketId).isNotNull();
    assertThat(ticketRowCountAsAdmin()).isEqualTo(1L);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);

    // (2) The entitlement dependency goes down: stop Redis so the next cache lookup cannot answer.
    DEDICATED_REDIS.stop();

    // (3) The gate must FAIL CLOSED — the connection failure propagates out of checkout and the
    // ticket is denied. It must NOT silently fall open and allow the still-"entitled" tenant
    // through.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> ticketService.checkout(checkoutRequest(service, barber, "outage-1"))))
        .as("the gate denies (throws) when entitlement state cannot be resolved, never falls open")
        .isInstanceOf(Exception.class);

    // Fail-closed proof: no NEW side effects. Still exactly the one ticket + one SaleRecorded from
    // the pre-outage success; the outage attempt wrote no row and emitted no event.
    assertThat(ticketRowCountAsAdmin()).isEqualTo(1L);
    assertThat(saleRecordedCountAsAdmin()).isEqualTo(1L);
  }

  private static CheckoutRequest checkoutRequest(
      CatalogItemResponse service, StaffProfileResponse barber, String idempotencyKey) {
    return new CheckoutRequest(
        OUTLET,
        idempotencyKey,
        "chair-1",
        barber.id(),
        null,
        List.of(new TicketLineInput(ItemType.SERVICE, service.id(), 1)),
        new PaymentRequest(TenderType.CASH, 100_000_00L));
  }

  private long ticketRowCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM barbershop_ticket");
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
