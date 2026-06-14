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
 * Test (b) — a stub-simulated send FAILURE is RECORDED, not swallowed.
 *
 * <p>With {@code native.notification.sender.simulate-failure=true}, the STUB transport reports
 * every delivery as FAILED. Consuming a {@code ConsolidationClosed} then yields a notification with
 * status FAILED, a {@code delivery_receipt} with status FAILED (and a null provider_ref), and a
 * FAILED {@code DeliveryReceipt} outbox event — the failure is captured for downstream visibility,
 * never silently dropped (HR-3), and the trigger is NOT DLT'd (the handler returns normally). No
 * money or PII is involved.
 *
 * <p>Because this needs a different sender property, it boots its own {@code @SpringBootTest}
 * context (cached separately by the property override). Rows are read over the admin/BYPASSRLS
 * connection.
 */
@SpringBootTest(properties = "native.notification.sender.simulate-failure=true")
class ConsolidationClosedFailedDeliveryTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  @Test
  void aSimulatedSendFailureRecordsAFailedNotificationAndReceipt() throws Exception {
    UUID triggerEventId = UUID.randomUUID();
    GenericRecord trigger = ConsolidationClosedFixtures.record(TENANT_A, "2026-06");
    ConsolidationClosedFixtures.publish(
        KAFKA.getBootstrapServers(), TENANT_A, triggerEventId, trigger);

    // The notification is created but its delivery FAILED — status FAILED, recorded not swallowed.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(notificationStatusByTriggerAsAdmin(triggerEventId)).isEqualTo("FAILED"));

    Map<String, Object> notification = notificationByTriggerAsAdmin(triggerEventId);
    assertThat(notification.get("status")).isEqualTo("FAILED");

    // A FAILED delivery_receipt is persisted (the failure is on the books) with no provider_ref.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(receiptCountAsAdmin()).isEqualTo(1L));
    Map<String, Object> receipt = receiptByNotificationAsAdmin((UUID) notification.get("id"));
    assertThat(receipt.get("status")).isEqualTo("FAILED");
    assertThat(receipt.get("provider_ref")).isNull();

    // A FAILED DeliveryReceipt event was still emitted to the outbox (downstream visibility).
    assertThat(outboxDeliveryReceiptStatusAsAdmin()).isEqualTo("FAILED");
  }

  private String notificationStatusByTriggerAsAdmin(UUID triggerEventId) throws Exception {
    Map<String, Object> row = notificationByTriggerAsAdmin(triggerEventId);
    return row == null ? null : (String) row.get("status");
  }

  private Map<String, Object> notificationByTriggerAsAdmin(UUID triggerEventId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id, status FROM notification WHERE trigger_event_id = ?")) {
      ps.setObject(1, triggerEventId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return Map.of("id", rs.getObject("id"), "status", rs.getString("status"));
      }
    }
  }

  private long receiptCountAsAdmin() throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM delivery_receipt")) {
      rs.next();
      return rs.getLong(1);
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
        java.util.HashMap<String, Object> row = new java.util.HashMap<>();
        row.put("status", rs.getString("status"));
        row.put("provider_ref", rs.getString("provider_ref"));
        return row;
      }
    }
  }

  private String outboxDeliveryReceiptStatusAsAdmin() throws Exception {
    // The outbox payload is raw Avro; assert the row exists and decode just enough to read status.
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'DeliveryReceipt'")) {
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        byte[] payload = rs.getBytes("payload");
        org.apache.avro.generic.GenericRecord decoded =
            id.co.nativeapp.events.AvroSerde.deserialize(
                payload, id.co.nativeapp.notification.notification.DeliveryReceiptSchema.schema());
        return decoded.get("status").toString();
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
