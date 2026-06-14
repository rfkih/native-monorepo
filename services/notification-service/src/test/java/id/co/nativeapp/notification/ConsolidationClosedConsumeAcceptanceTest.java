package id.co.nativeapp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (a) — end-to-end CONSUME of {@code ConsolidationClosed}: a notification is created,
 * delivered via the STUB transport (status SENT), and a {@code delivery_receipt} is persisted
 * (DELIVERED), and a {@code DeliveryReceipt} event is written to the outbox — idempotently (a
 * re-delivery creates no duplicate notification/receipt).
 *
 * <p>Publish one {@code ConsolidationClosed} for a tenant/period; await the notification + receipt
 * rows; assert their status and the outbox row. Then re-deliver the SAME event id, followed by a
 * distinct marker trigger on the same partition; once the marker's notification appears (2
 * notifications total) the duplicate is necessarily drained — so the no-duplicate assertion is
 * race-free. Awaitility awaits the async consumption (no {@code Thread.sleep}). Rows are read over
 * the admin/BYPASSRLS connection (the tables are FORCE RLS).
 */
@SpringBootTest
class ConsolidationClosedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  @Test
  void consumingAConsolidationClosedCreatesDeliversAndReceiptsIdempotently() throws Exception {
    String period = "2026-06";
    UUID triggerEventId = UUID.randomUUID();
    GenericRecord trigger = ConsolidationClosedFixtures.record(TENANT_A, period);
    ConsolidationClosedFixtures.publish(
        KAFKA.getBootstrapServers(), TENANT_A, triggerEventId, trigger);

    // The notification is created + delivered via the stub (SENT).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(notificationCountAsAdmin()).isEqualTo(1L));

    Map<String, Object> notification = notificationByTriggerAsAdmin(triggerEventId);
    assertThat(notification.get("status")).isEqualTo("SENT");
    assertThat(notification.get("channel")).isEqualTo("EMAIL");
    assertThat(notification.get("company_id")).isEqualTo(TENANT_A);
    assertThat(((String) notification.get("subject"))).contains(period);

    // A DELIVERED delivery_receipt is persisted with a synthetic provider_ref.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(receiptCountAsAdmin()).isEqualTo(1L));
    Map<String, Object> receipt = receiptByNotificationAsAdmin((UUID) notification.get("id"));
    assertThat(receipt.get("status")).isEqualTo("DELIVERED");
    assertThat((String) receipt.get("provider_ref")).startsWith("stub-");

    // A DeliveryReceipt event was written to the outbox (the producer side, rule 3).
    assertThat(outboxCountAsAdmin("DeliveryReceipt")).isEqualTo(1L);

    // IDEMPOTENCY: re-deliver the SAME trigger event id, then a distinct marker trigger after it on
    // the same partition; once the marker posts (2 notifications), the duplicate is drained — so
    // the
    // no-double assertion is race-free.
    ConsolidationClosedFixtures.publish(
        KAFKA.getBootstrapServers(), TENANT_A, triggerEventId, trigger);

    UUID markerEventId = UUID.randomUUID();
    GenericRecord marker = ConsolidationClosedFixtures.record(TENANT_A, "2026-07");
    ConsolidationClosedFixtures.publish(
        KAFKA.getBootstrapServers(), TENANT_A, markerEventId, marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(notificationCountAsAdmin()).isEqualTo(2L));

    // The duplicate trigger created NO second notification/receipt/event for the original trigger.
    assertThat(notificationCountAsAdmin()).isEqualTo(2L); // original + marker only
    assertThat(receiptCountAsAdmin()).isEqualTo(2L);
    assertThat(outboxCountAsAdmin("DeliveryReceipt")).isEqualTo(2L);
  }

  private long notificationCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM notification");
  }

  private long receiptCountAsAdmin() throws Exception {
    return countAsAdmin("SELECT count(*) FROM delivery_receipt");
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

  private long countAsAdmin(String sql) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private Map<String, Object> notificationByTriggerAsAdmin(UUID triggerEventId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id, status, channel, company_id, subject FROM notification WHERE"
                    + " trigger_event_id = ?")) {
      ps.setObject(1, triggerEventId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "id", rs.getObject("id"),
            "status", rs.getString("status"),
            "channel", rs.getString("channel"),
            "company_id", rs.getString("company_id"),
            "subject", rs.getString("subject"));
      }
    }
  }

  private Map<String, Object> receiptByNotificationAsAdmin(UUID notificationId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT status, provider_ref FROM delivery_receipt WHERE notification_id = ?")) {
      ps.setObject(1, notificationId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "status", rs.getString("status"),
            "provider_ref", rs.getString("provider_ref"));
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
