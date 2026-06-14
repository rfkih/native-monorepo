package id.co.nativeapp.notification.config;

import id.co.nativeapp.notification.config.NotificationProperties.SenderMode;
import id.co.nativeapp.notification.notification.NotificationSender;
import id.co.nativeapp.notification.notification.StubNotificationSender;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link NotificationSender} transport from config.
 *
 * <p><strong>Stub vs real, by property.</strong> {@code native.notification.sender.mode} picks the
 * transport: {@code STUB} (the only mode wired in this slice) records a synthetic delivery — no
 * real email/push provider is contacted. {@code SMTP} / {@code PUSH} are FUTURE real integrations;
 * until one ships, selecting it fails fast at startup (a clear, deliberate error rather than a
 * silent no-op), so the placeholder cannot be mistaken for a working provider. When the real
 * transport lands it is added here behind the same {@link NotificationSender} interface and the
 * consumer/ service code does not change.
 *
 * <p>The {@code @ConditionalOnMissingBean} lets a test supply its own {@link NotificationSender}
 * (e.g. a programmable stub) without a bean-definition clash.
 */
@Configuration
public class NotificationSenderConfig {

  /**
   * A UTC clock for the stub's {@code delivered_at} timestamp; overridable for deterministic tests.
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock notificationClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public NotificationSender notificationSender(NotificationProperties properties, Clock clock) {
    SenderMode mode = properties.sender().mode();
    return switch (mode) {
      case STUB -> new StubNotificationSender(properties.sender().simulateFailure(), clock);
      case SMTP, PUSH ->
          throw new IllegalStateException(
              "Notification sender mode "
                  + mode
                  + " is a future integration and is not implemented yet; only STUB is wired in this"
                  + " slice. Set native.notification.sender.mode=STUB.");
    };
  }
}
