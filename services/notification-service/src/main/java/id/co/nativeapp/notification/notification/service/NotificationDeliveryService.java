package id.co.nativeapp.notification.notification.service;

import id.co.nativeapp.notification.config.NotificationProperties;
import id.co.nativeapp.notification.notification.domain.Channel;
import id.co.nativeapp.notification.notification.domain.Notification;
import id.co.nativeapp.notification.notification.messaging.ConsolidationClosedEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates turning a consumed {@code ConsolidationClosed} trigger into a delivered notification
 * — the notification counterpart of finance's posting service.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> notification-service
 * is purely downstream on this path; there is no JWT. So this service binds the tenant scope from
 * the event's {@code company_id} via {@link TenantContext#callAs} with a fixed {@code
 * "notification-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates
 * to the proxied {@link NotificationDeliveryWriter} so the {@code @Transactional} advice and the
 * auto-RLS aspect engage under that tenant. The actual transactional unit of work — dedupe + create
 * + deliver + receipt + outbox — lives in the writer (a separate bean so the Spring proxy is not
 * bypassed by self-invocation).
 *
 * <p><strong>Message composition.</strong> A concise "consolidation closed for &lt;period&gt;"
 * notice is composed here from the trigger and the configured placeholder recipient (the
 * per-company recipient directory is a future integration). This is a backend trigger summary, not
 * localized user-facing copy (that lives in the frontend i18n layer, HR-9).
 */
@Service
public class NotificationDeliveryService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "notification-consumer";

  /** The channel a consolidation-closed notice is sent on in this slice. */
  private static final Channel CONSOLIDATION_CHANNEL = Channel.EMAIL;

  private final NotificationDeliveryWriter writer;
  private final NotificationProperties properties;

  public NotificationDeliveryService(
      NotificationDeliveryWriter writer, NotificationProperties properties) {
    this.writer = writer;
    this.properties = properties;
  }

  /**
   * Handles one decoded {@code ConsolidationClosed}, idempotently: composes the notification, binds
   * the event's tenant for the posting transaction (so RLS applies), and delegates to the writer
   * which creates + delivers + receipts + publishes.
   *
   * @return {@code true} if this delivery handled (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(ConsolidationClosedEvent event) {
    Notification notification = compose(event);
    try {
      return TenantContext.callAs(
          event.companyId(), CONSUMER_ACTOR, () -> writer.deliver(event.eventId(), notification));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.deliver throws only unchecked, so this is
      // unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle ConsolidationClosed", e);
    }
  }

  /** Composes the PENDING consolidation-closed notification (subject + body) from the trigger. */
  private Notification compose(ConsolidationClosedEvent event) {
    String scope =
        event.group().map(groupId -> "group " + groupId).orElse("company " + event.companyId());
    String subject = "Consolidation closed for " + event.period();
    String body =
        "The financial consolidation for "
            + scope
            + " has been closed for period "
            + event.period()
            + ".";
    return new Notification(
        CONSOLIDATION_CHANNEL, properties.defaultRecipient(), subject, body, event.eventId());
  }
}
