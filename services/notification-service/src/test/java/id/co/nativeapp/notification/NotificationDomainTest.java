package id.co.nativeapp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.notification.notification.Channel;
import id.co.nativeapp.notification.notification.DeliveryOutcome;
import id.co.nativeapp.notification.notification.DeliveryStatus;
import id.co.nativeapp.notification.notification.Notification;
import id.co.nativeapp.notification.notification.NotificationStatus;
import id.co.nativeapp.notification.notification.StubNotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain + stub-transport tests (no Spring): the {@link Notification} aggregate
 * invariants and lifecycle, and the {@link StubNotificationSender} success/failure outcomes.
 */
class NotificationDomainTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-06-14T08:30:00Z"), ZoneOffset.UTC);

  @Test
  void aNewNotificationIsPendingAndKeepsItsTrigger() {
    UUID trigger = UUID.randomUUID();
    Notification n =
        new Notification(Channel.EMAIL, "owner@example.co.id", "Subject", "Body", trigger);
    assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
    assertThat(n.getChannel()).isEqualTo(Channel.EMAIL);
    assertThat(n.getTriggerEventId()).isEqualTo(trigger);
    assertThat(n.getId()).isNotNull();
  }

  @Test
  void markSentAndMarkFailedMoveTheStatus() {
    Notification n = new Notification(Channel.PUSH, "device-token", "S", "B", UUID.randomUUID());
    n.markSent();
    assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    n.markFailed();
    assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  void aBlankRecipientIsRejected() {
    assertThatThrownBy(() -> new Notification(Channel.EMAIL, "  ", "S", "B", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullChannelIsRejected() {
    assertThatThrownBy(
            () -> new Notification(null, "owner@example.co.id", "S", "B", UUID.randomUUID()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theStubReportsASuccessfulDeliveryWithASyntheticProviderRef() {
    StubNotificationSender sender = new StubNotificationSender(false, FIXED);
    Notification n =
        new Notification(Channel.EMAIL, "owner@example.co.id", "S", "B", UUID.randomUUID());

    DeliveryOutcome outcome = sender.deliver(n);

    assertThat(outcome.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(outcome.isDelivered()).isTrue();
    assertThat(outcome.providerRef()).startsWith("stub-");
    assertThat(outcome.at()).isEqualTo(Instant.parse("2026-06-14T08:30:00Z"));
  }

  @Test
  void theStubCanSimulateAFailureWithNoProviderRef() {
    StubNotificationSender sender = new StubNotificationSender(true, FIXED);
    Notification n =
        new Notification(Channel.EMAIL, "owner@example.co.id", "S", "B", UUID.randomUUID());

    DeliveryOutcome outcome = sender.deliver(n);

    assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(outcome.isDelivered()).isFalse();
    assertThat(outcome.providerRef()).isNull();
  }
}
