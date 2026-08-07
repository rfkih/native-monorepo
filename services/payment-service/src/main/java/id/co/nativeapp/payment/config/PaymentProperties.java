package id.co.nativeapp.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized PSP-integration configuration (12-factor, ENGINEERING-STANDARDS §7).
 *
 * <ul>
 *   <li>{@code native.payment.webhook-base-url} — the PUBLIC gateway origin Midtrans must reach
 *       (e.g. the UAT Funnel URL). When non-blank, every charge stamps its per-transaction callback
 *       {@code <base>/api/v1/psp-webhooks/midtrans/<companyId>} via Midtrans's {@code
 *       X-Override-Notification} header — zero merchant dashboard configuration. Blank (the dev
 *       default) omits the header; settlement then arrives via the {@code /sync} fallback.
 *   <li>{@code native.payment.midtrans.*} — base URLs per environment (overridable so tests point
 *       at a stub server), timeouts, and the QR expiry window.
 * </ul>
 */
@Validated
@ConfigurationProperties("native.payment")
public record PaymentProperties(String webhookBaseUrl, @NotNull Midtrans midtrans) {

  /** Midtrans Core API knobs. */
  public record Midtrans(
      @NotBlank String sandboxBaseUrl,
      @NotBlank String productionBaseUrl,
      int connectTimeoutMs,
      int readTimeoutMs,
      int expiryMinutes) {}
}
