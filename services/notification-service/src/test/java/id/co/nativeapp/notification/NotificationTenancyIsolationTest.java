package id.co.nativeapp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.notification.notification.ConsolidationClosedEvent;
import id.co.nativeapp.notification.notification.DeliveryReceipt;
import id.co.nativeapp.notification.notification.Notification;
import id.co.nativeapp.notification.notification.NotificationDeliveryService;
import id.co.nativeapp.notification.notification.NotificationReader;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (c) — the tenancy isolation proof, relying on AUTO-applied RLS.
 *
 * <p>A notification created (and its receipt) under tenant A is invisible to tenant B. The read
 * path ({@link NotificationReader}) carries NO {@code WHERE company_id} and never calls the
 * synchronizer by hand — only {@link RlsAutoApplyAspect} sets the tenant GUC on each
 * {@code @Transactional} unit of work. This models a developer who forgets the tenant and proves
 * they are still protected (rule 5). It drives the delivery service directly (no Kafka needed for
 * an RLS proof), binding each tenant via {@link TenantContext#callAs}.
 *
 * <p>Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} in the baseline binds even that owning role.
 */
@SpringBootTest
class NotificationTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "viewer-a@example.co.id";
  private static final String ACTOR_B = "viewer-b@example.co.id";

  @Autowired private NotificationDeliveryService deliveryService;
  @Autowired private NotificationReader reader;

  @Test
  void aNotificationCreatedUnderTenantAIsInvisibleToTenantB() throws Exception {
    UUID eventA = UUID.randomUUID();
    UUID eventB = UUID.randomUUID();

    // The service binds the tenant from the EVENT's company_id internally.
    deliveryService.handle(new ConsolidationClosedEvent(eventA, TENANT_A, null, "2026-06"));
    deliveryService.handle(new ConsolidationClosedEvent(eventB, TENANT_B, null, "2026-06"));

    // Inside A's scope: only A's notification + receipt are visible. RLS only — no WHERE clause.
    List<UUID> aNotifications =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                reader.findAllNotificationsForCurrentTenant().stream()
                    .map(Notification::getTriggerEventId)
                    .toList());
    assertThat(aNotifications).containsExactly(eventA);
    assertThat(aNotifications).doesNotContain(eventB);

    List<UUID> aReceiptNotifications =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                reader.findAllReceiptsForCurrentTenant().stream()
                    .map(DeliveryReceipt::getNotificationId)
                    .toList());
    assertThat(aReceiptNotifications).hasSize(1);

    // The inverse: inside B's scope only B's notification is visible.
    List<UUID> bNotifications =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                reader.findAllNotificationsForCurrentTenant().stream()
                    .map(Notification::getTriggerEventId)
                    .toList());
    assertThat(bNotifications).containsExactly(eventB);
    assertThat(bNotifications).doesNotContain(eventA);
  }
}
