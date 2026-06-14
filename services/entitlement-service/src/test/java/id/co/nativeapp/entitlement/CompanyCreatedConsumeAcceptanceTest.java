package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (a) — the goal: consuming {@code CompanyCreated} grants the DEFAULT entitlements and emits
 * exactly one {@code EntitlementGranted} per module, idempotently on re-delivery.
 *
 * <p>Publish a {@code CompanyCreated} Avro message (bytes built via {@code libs/events AvroSerde})
 * to the real Kafka topic; entitlement-service consumes it; one {@code tenant_entitlement} row is
 * created per default module (status {@code ENTITLED}) for the right company, AND exactly one
 * {@code EntitlementGranted} outbox row exists per module. A second delivery with the SAME event id
 * is a clean no-op (the idempotent consumer): no extra rows, no extra events. Awaitility awaits the
 * async consumption (no {@code Thread.sleep}).
 *
 * <p>The full context boots, so the auto-RLS aspect is active, Flyway has run (seeding {@code
 * module_catalog}), and Hibernate {@code ddl-auto=validate} has verified the mapping. The listener
 * binds the event's {@code company_id} as the tenant, so the grant writes pass the RLS {@code WITH
 * CHECK} for the brand-new tenant.
 */
@SpringBootTest
class CompanyCreatedConsumeAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  /** The defaults configured in application.yml (the base verticals + the platform modules). */
  private static final List<String> DEFAULT_MODULES =
      List.of("restaurant", "carwash", "laundromat", "hr", "finance");

  @Test
  void consumingCompanyCreatedGrantsTheDefaultsAndEmitsOneEntitlementGrantedPerModule()
      throws Exception {
    UUID eventId = UUID.randomUUID();
    GenericRecord event = CompanyCreatedFixtures.record(TENANT_A, "IDR", "id");
    CompanyCreatedFixtures.publish(KAFKA.getBootstrapServers(), TENANT_A, eventId, event);

    // Await one tenant_entitlement row per default module (over the admin/BYPASSRLS connection,
    // since
    // the table is FORCE RLS and an unscoped count would otherwise be 0).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(entitlementCountAsAdmin(TENANT_A)).isEqualTo(DEFAULT_MODULES.size()));

    // Each default module has exactly one ENTITLED row.
    for (String moduleKey : DEFAULT_MODULES) {
      assertThat(entitlementStatusAsAdmin(TENANT_A, moduleKey)).isEqualTo("ENTITLED");
    }

    // Exactly one EntitlementGranted outbox event per module — and no revoke events.
    assertThat(outboxCountAsAdmin("EntitlementGranted")).isEqualTo(DEFAULT_MODULES.size());
    assertThat(outboxCountAsAdmin("EntitlementRevoked")).isZero();
  }

  @Test
  void redeliveringTheSameCompanyCreatedDoesNotDoubleGrant() throws Exception {
    UUID eventId = UUID.randomUUID();
    GenericRecord event = CompanyCreatedFixtures.record(TENANT_A, "IDR", "id");

    // Deliver the SAME event id twice (at-least-once redelivery of one logical event).
    CompanyCreatedFixtures.publish(KAFKA.getBootstrapServers(), TENANT_A, eventId, event);
    CompanyCreatedFixtures.publish(KAFKA.getBootstrapServers(), TENANT_A, eventId, event);

    // A distinct company published after the duplicate; once ITS defaults land, the duplicate has
    // necessarily already drained from the same partition consumer (records on one key go to one
    // partition, consumed in order), making the no-double-grant assertion race-free.
    String tenantB = "22222222-2222-2222-2222-222222222222";
    CompanyCreatedFixtures.publish(
        KAFKA.getBootstrapServers(),
        tenantB,
        UUID.randomUUID(),
        CompanyCreatedFixtures.record(tenantB, "USD", "en"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(entitlementCountAsAdmin(tenantB)).isEqualTo(DEFAULT_MODULES.size()));

    // Company A still has exactly one row per module (not doubled), and one event per module.
    assertThat(entitlementCountAsAdmin(TENANT_A)).isEqualTo(DEFAULT_MODULES.size());
    assertThat(outboxCountForCompanyAsAdmin(TENANT_A, "EntitlementGranted"))
        .isEqualTo(DEFAULT_MODULES.size());
  }

  private long entitlementCountAsAdmin(String companyId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM tenant_entitlement WHERE company_id = ?")) {
      ps.setString(1, companyId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String entitlementStatusAsAdmin(String companyId, String moduleKey) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT status FROM tenant_entitlement WHERE company_id = ? AND module_key = ?")) {
      ps.setString(1, companyId);
      ps.setString(2, moduleKey);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private long outboxCountAsAdmin(String eventType) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM outbox WHERE event_type = ?")) {
      ps.setString(1, eventType);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long outboxCountForCompanyAsAdmin(String companyId, String eventType) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM outbox WHERE company_id = ?::uuid AND event_type = ?")) {
      ps.setString(1, companyId);
      ps.setString(2, eventType);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
