package id.co.nativeapp.notification.notification.service;

import id.co.nativeapp.notification.notification.domain.DeliveryOutcome;
import id.co.nativeapp.notification.notification.domain.Notification;

/**
 * The transport seam: delivers a {@link Notification} through some channel (email/push) and returns
 * a {@link DeliveryOutcome}.
 *
 * <p><strong>The real SMTP/push provider is a FUTURE integration.</strong> This slice ships only a
 * stub implementation ({@code StubNotificationSender}) that records a successful delivery with a
 * synthetic {@code provider_ref} (and can be configured to simulate a failure). A real
 * implementation — an SMTP client, an FCM/APNs push client — is selected behind this same interface
 * by a property/profile (see {@code NotificationSenderConfig}: {@code
 * native.notification.sender.mode}), so the consumer/service code never changes when the real
 * transport lands. No real provider credentials, endpoints, or SDKs are present in this service
 * yet.
 *
 * <p><strong>A failure is a return value, not an exception.</strong> A delivery failure must be
 * returned as a FAILED {@link DeliveryOutcome} so the caller can RECORD it (a FAILED notification +
 * receipt + event) in the same transaction. Throwing would unwind the consumer and DLT the trigger,
 * losing the recorded failure — exactly what HR-3 forbids (the failure must not be swallowed).
 */
public interface NotificationSender {

  /**
   * Attempts to deliver the notification.
   *
   * @param notification the notification to deliver
   * @return the outcome (DELIVERED with a provider_ref, or FAILED) — never throws for a normal
   *     delivery failure
   */
  DeliveryOutcome deliver(Notification notification);
}
