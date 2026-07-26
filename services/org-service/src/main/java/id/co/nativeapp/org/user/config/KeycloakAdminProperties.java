package id.co.nativeapp.org.user.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Explicit, owned connect + read timeouts and credentials for the Keycloak Admin REST API client
 * (ENGINEERING-STANDARDS §4 — every outbound client sets explicit timeouts, owned in our config).
 *
 * <p>Bound under {@code native.keycloak-admin}. A startup self-check (mirroring {@link
 * id.co.nativeapp.security.OutboundClientTimeoutCheck}) fails fast on a non-positive timeout.
 * Properties are {@code @NotNull}: the app refuses to start if any required field is missing.
 *
 * <p><strong>Secret handling.</strong> {@code clientSecret} is injected from {@code
 * ${KEYCLOAK_ADMIN_SECRET}} in production; the dev default ({@code native-admin-secret}) is the
 * value imported into the dev Keycloak realm and is NOT a real secret. Never log this field
 * (credential hygiene — rule 6 adjacent).
 */
@Validated
@ConfigurationProperties("native.keycloak-admin")
public class KeycloakAdminProperties {

  /** The base URL of the Keycloak server (e.g. {@code http://localhost:8080}). */
  @NotNull private URI baseUrl;

  /** The realm name (e.g. {@code native}). */
  @NotNull private String realm;

  /** The {@code client_id} of the service-account client ({@code native-admin}). */
  @NotNull private String clientId;

  /**
   * The client secret. Injected from {@code ${KEYCLOAK_ADMIN_SECRET}}; dev default is the dev-realm
   * value. NEVER log this field.
   */
  @NotNull private String clientSecret;

  /** HTTP connect timeout for Keycloak Admin REST calls. Default {@code 2s}. */
  @NotNull private Duration connectTimeout = Duration.ofSeconds(2);

  /** HTTP read timeout for Keycloak Admin REST calls. Default {@code 5s}. */
  @NotNull private Duration readTimeout = Duration.ofSeconds(5);

  /**
   * When {@code true}, a signed-up owner is created with the {@code VERIFY_EMAIL} required action
   * (and {@code emailVerified=false}), so Keycloak forces email verification at first login.
   *
   * <p>Default {@code false} because verification needs a working realm SMTP configuration —
   * enabling it without SMTP locks every new owner out at the verify screen. <strong>Production
   * MUST set this {@code true}</strong> (with realm SMTP configured): without it anyone can
   * register an address they do not own, and a typo in the email is an unrecoverable account.
   */
  private boolean requireEmailVerification = false;

  public URI getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(URI baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getRealm() {
    return realm;
  }

  public void setRealm(String realm) {
    this.realm = realm;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public boolean isRequireEmailVerification() {
    return requireEmailVerification;
  }

  public void setRequireEmailVerification(boolean requireEmailVerification) {
    this.requireEmailVerification = requireEmailVerification;
  }
}
