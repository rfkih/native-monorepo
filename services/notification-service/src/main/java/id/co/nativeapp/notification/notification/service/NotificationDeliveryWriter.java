package id.co.nativeapp.notification.notification.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.notification.notification.domain.DeliveryOutcome;
import id.co.nativeapp.notification.notification.domain.DeliveryReceipt;
import id.co.nativeapp.notification.notification.domain.Notification;
import id.co.nativeapp.notification.notification.messaging.DeliveryReceiptSchema;
import id.co.nativeapp.notification.notification.repository.DeliveryReceiptRepository;
import id.co.nativeapp.notification.notification.repository.NotificationRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work behind {@link NotificationDeliveryService}:
 * it creates the notification, DELIVERS it through the stubbed {@link NotificationSender}, persists
 * the {@code delivery_receipt}, marks the notification SENT/FAILED, and writes the {@code
 * DeliveryReceipt} outbox row — idempotently, all in ONE transaction.
 *
 * <p>It is a distinct bean (not a private method on {@link NotificationDeliveryService}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC — and the GUC is exactly what makes the RLS {@code WITH CHECK} pass on the inserts
 * (rule 5). The caller binds the tenant from the event's {@code company_id} before invoking this.
 *
 * <p><strong>Idempotency + atomicity (rule 3 / HR-3).</strong> Everything below happens in ONE
 * transaction: the {@link ProcessedEventStore#processOnce} dedupe claim and the side effects commit
 * (or roll back) together. A re-delivered trigger (same event id) is a clean no-op — no second
 * notification, no second receipt, no second {@code DeliveryReceipt} event. The {@code
 * trigger_event_id UNIQUE} on {@code notification} and the {@code notification_id UNIQUE} on {@code
 * delivery_receipt} are the database backstops.
 *
 * <p><strong>A delivery FAILURE is recorded, not swallowed.</strong> The {@link NotificationSender}
 * returns a FAILED {@link DeliveryOutcome} (it does not throw for a normal failure), so the writer
 * still persists the notification (status FAILED), a FAILED {@code delivery_receipt}, and a FAILED
 * {@code DeliveryReceipt} outbox event — the failure is captured for downstream visibility. Because
 * the handler returns normally, the trigger is NOT DLT'd and the offset commits; the recorded
 * failure is the durable outcome. (A real transport's transient error would instead be surfaced as
 * a thrown exception to trigger the bounded-retry/DLT path — out of scope for the stub.)
 */
@Component
public class NotificationDeliveryWriter {

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWriter.class);

  private final NotificationRepository notificationRepository;
  private final DeliveryReceiptRepository receiptRepository;
  private final NotificationSender sender;
  private final OutboxWriter outboxWriter;
  private final ProcessedEventStore processedEvents;

  public NotificationDeliveryWriter(
      NotificationRepository notificationRepository,
      DeliveryReceiptRepository receiptRepository,
      NotificationSender sender,
      OutboxWriter outboxWriter,
      ProcessedEventStore processedEvents) {
    this.notificationRepository = notificationRepository;
    this.receiptRepository = receiptRepository;
    this.sender = sender;
    this.outboxWriter = outboxWriter;
    this.processedEvents = processedEvents;
  }

  /**
   * Creates + delivers + receipts + publishes for the trigger, exactly once per event id. Must be
   * called inside a {@link TenantContext} scope bound to the trigger's {@code company_id} (the
   * auto-RLS aspect sets the tenant GUC for this transaction from that scope).
   *
   * @param eventId the trigger event UUID (the dedupe key)
   * @param notification the composed PENDING notification to create and deliver
   * @return {@code true} if this delivery handled (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery).
   */
  @Transactional
  public boolean deliver(UUID eventId, Notification notification) {
    return processedEvents.processOnce(eventId, () -> createDeliverAndReceipt(notification));
  }

  private void createDeliverAndReceipt(Notification notification) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // 1) Create the notification (PENDING) under the bound tenant. setCompanyId stamps the tenant
    // so
    //    the RLS WITH CHECK passes; the trigger_event_id UNIQUE is the dedupe backstop.
    notification.setCompanyId(companyId);
    notificationRepository.save(notification);

    // 2) DELIVER through the stubbed transport. A failure is RETURNED (FAILED outcome), never
    // thrown,
    //    so it is recorded below rather than unwinding the consumer and being lost (HR-3).
    DeliveryOutcome outcome = sender.deliver(notification);

    // 3) Move the notification to its terminal status from the outcome.
    if (outcome.isDelivered()) {
      notification.markSent();
    } else {
      notification.markFailed();
      log.warn(
          "Delivery FAILED for notification={} channel={}; recording a FAILED receipt (not"
              + " swallowed)",
          notification.getId(),
          notification.getChannel());
    }
    notificationRepository.save(notification);

    // 4) Persist the terminal delivery_receipt (DELIVERED or FAILED) under the bound tenant.
    DeliveryReceipt receipt =
        new DeliveryReceipt(
            notification.getId(), outcome.status(), outcome.providerRef(), outcome.at());
    receipt.setCompanyId(companyId);
    receiptRepository.save(receipt);

    // 5) Publish the DeliveryReceipt event via the transactional outbox (rule 3) — the outbox row
    //    commits atomically with the notification + receipt. Emitted for BOTH outcomes so a failure
    //    is visible downstream. NO PII in the payload (rule 6).
    GenericRecord event = DeliveryReceiptSchema.toRecord(notification, receipt, companyId);
    outboxWriter.write(
        DeliveryReceiptSchema.AGGREGATE_TYPE,
        notification.getId().toString(),
        DeliveryReceiptSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        receipt.getDeliveredAt());
  }
}
