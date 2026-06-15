package id.co.nativeapp.notification.notification.service;

import id.co.nativeapp.notification.notification.domain.DeliveryOutcome;
import id.co.nativeapp.notification.notification.domain.Notification;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The no-real-provider STUB transport. It does NOT contact any email/push provider — it synthesises
 * a delivery outcome so the end-to-end consume -> create -> deliver -> receipt -> publish loop can
 * be exercised without an external dependency.
 *
 * <p>By default it records a SUCCESSFUL delivery with a synthetic {@code provider_ref} (a {@code
 * stub-<uuid>} reference). When configured with {@code simulateFailure=true} (via {@code
 * native.notification.sender.simulate-failure}) it records a FAILED delivery instead — the path
 * that proves a send failure is RECORDED (a FAILED notification + receipt + event), never
 * swallowed.
 *
 * <p><strong>Future integration.</strong> The real SMTP/push providers replace this behind the
 * {@link NotificationSender} interface, selected by {@code native.notification.sender.mode}; see
 * {@code NotificationSenderConfig}. The recipient is treated as opaque and is never logged.
 */
public class StubNotificationSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(StubNotificationSender.class);

  private final boolean simulateFailure;
  private final Clock clock;

  /**
   * @param simulateFailure when true, every delivery is reported as FAILED (the recorded-failure
   *     path); when false, every delivery is reported as DELIVERED with a synthetic provider_ref
   * @param clock the clock for the {@code delivered_at} timestamp (UTC); injectable for tests
   */
  public StubNotificationSender(boolean simulateFailure, Clock clock) {
    this.simulateFailure = simulateFailure;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public DeliveryOutcome deliver(Notification notification) {
    Objects.requireNonNull(notification, "notification");
    Instant at = Instant.now(clock);
    if (simulateFailure) {
      // A simulated transport failure: returned (not thrown) so the caller records it. The
      // recipient is opaque and never logged; only the non-PII ids/channel appear.
      log.warn(
          "STUB transport: simulated delivery FAILURE for notification={} channel={}",
          notification.getId(),
          notification.getChannel());
      return DeliveryOutcome.failed(null, at);
    }
    String providerRef = "stub-" + UUID.randomUUID();
    log.debug(
        "STUB transport: delivered notification={} channel={} providerRef={}",
        notification.getId(),
        notification.getChannel(),
        providerRef);
    return DeliveryOutcome.delivered(providerRef, at);
  }
}
