package id.co.nativeapp.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized notification-service config, bound to {@code native.notification} and validated at
 * startup (fail fast — ENGINEERING-STANDARDS §7). No business config is hardcoded in Java.
 *
 * @param defaultRecipient the placeholder recipient a consolidation-closed notification is
 *     addressed to in this slice (the per-company recipient directory is a future integration). A
 *     destination, not a hardcoded user-facing string.
 * @param sender the stubbed-transport selection (mode + a failure-simulation toggle).
 */
@Validated
@ConfigurationProperties("native.notification")
public record NotificationProperties(
    @NotBlank String defaultRecipient, @NotNull @jakarta.validation.Valid Sender sender) {

  /**
   * The transport selection for the stubbed {@code NotificationSender}.
   *
   * @param mode which transport to use. {@code STUB} (the only mode wired in this slice) records a
   *     synthetic delivery; {@code SMTP}/{@code PUSH} are future real integrations selected here.
   * @param simulateFailure when {@code true}, the {@code STUB} transport simulates a delivery
   *     FAILURE instead of a success — the failure is recorded, never swallowed.
   */
  public record Sender(@NotNull SenderMode mode, boolean simulateFailure) {}

  /** The transport mode. Only {@link #STUB} is implemented in this slice. */
  public enum SenderMode {
    /** The no-real-provider stub: records a synthetic delivery (or, if configured, a failure). */
    STUB,
    /** Future: a real SMTP email provider. Selecting it today fails fast (not yet implemented). */
    SMTP,
    /** Future: a real push (e.g. FCM) provider. Selecting it today fails fast. */
    PUSH
  }
}
