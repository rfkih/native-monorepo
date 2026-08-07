package id.co.nativeapp.payment.settings.domain;

import id.co.nativeapp.payment.config.PiiBytesAttributeConverter;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code payment_settings} aggregate (ADR 0045) — one row per scope: the company DEFAULT row
 * ({@code outletId == null}) or a per-outlet OVERRIDE row. Owns the QRIS {@link QrisMode mode}, the
 * merchant's uploaded static QRIS image, and (company default row only) the merchant's own Midtrans
 * credentials.
 *
 * <p><strong>Credentials (rule 6).</strong> {@code serverKey}/{@code clientKey} are mapped through
 * {@link PiiBytesAttributeConverter} so the {@code *_encrypted BYTEA} columns hold AES-256-GCM
 * ciphertext; the entity fields hold plaintext ONLY while loaded inside a writer transaction and
 * are never exposed by any read path — every read endpoint is served from a projection that never
 * selects the encrypted columns, and the display affordance is {@link #getServerKeyLast4()}. {@link
 * #toString()} is redacted.
 *
 * <p><strong>Half-configured is legal.</strong> A mode may be set before its prerequisites exist
 * (STATIC before an image, GATEWAY before credentials): the effective read exposes availability and
 * the console fails open to MANUAL, so a half-configured mode can never block a sale. The charge
 * writer independently refuses to charge without credentials.
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code payment_settings} RLS policy (rule 5,
 * V2).
 */
@Entity
@Table(name = "payment_settings")
public class PaymentSettings extends Auditable {

  /**
   * The server-enforced image size cap (2 MiB) — mirrors the DB {@code CHECK} constraint and the
   * {@code spring.servlet.multipart.max-file-size} property (defense in depth: {@code
   * SettingsWriter} re-checks this even though Spring's multipart resolver already caps the part).
   */
  public static final int MAX_QR_IMAGE_BYTES = 2 * 1024 * 1024;

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "outlet_id", updatable = false)
  private UUID outletId;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, length = 16)
  private QrisMode mode;

  @Column(name = "static_qr_content_type", length = 64)
  private String staticQrContentType;

  @Column(name = "static_qr_byte_size")
  private Integer staticQrByteSize;

  // CHAR(64) (bpchar), not VARCHAR — a sha256 hex digest is always exactly 64 chars (the
  // expense_receipt idiom: @JdbcTypeCode(SqlTypes.CHAR) matches the V2 CHAR(64) column so
  // Hibernate's schema VALIDATION does not flag a bpchar/varchar mismatch).
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "static_qr_sha256", length = 64)
  private String staticQrSha256;

  @Column(name = "static_qr_data")
  private byte[] staticQrData;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", length = 16)
  private PspProvider provider;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_environment", length = 16)
  private ProviderEnvironment providerEnvironment;

  @Convert(converter = PiiBytesAttributeConverter.class)
  @Column(name = "server_key_encrypted")
  private String serverKey;

  @Convert(converter = PiiBytesAttributeConverter.class)
  @Column(name = "client_key_encrypted")
  private String clientKey;

  @Column(name = "server_key_last4", length = 4)
  private String serverKeyLast4;

  protected PaymentSettings() {
    // for JPA
  }

  /**
   * Creates a settings row for a scope.
   *
   * @param outletId the outlet this row overrides, or {@code null} for the company default row
   * @param mode the initial mode; must be non-null
   */
  public PaymentSettings(UUID outletId, QrisMode mode) {
    this.id = UUID.randomUUID();
    this.outletId = outletId;
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  /** Changes the row's mode. */
  public void changeMode(QrisMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  /**
   * Applies GATEWAY credentials (company default row only — enforced by the writer). {@code
   * serverKey} is WRITE-ONLY at the API: a {@code null} keeps the previously stored key (the
   * console re-sends the form without it), a non-blank value replaces it and refreshes {@code
   * serverKeyLast4}.
   *
   * @throws SettingsValidationException if this is an outlet override row (credentials are
   *     company-level), or a key is provided without provider/environment
   */
  public void applyGatewayCredentials(
      PspProvider provider, ProviderEnvironment environment, String serverKey, String clientKey) {
    if (outletId != null) {
      throw new SettingsValidationException(
          "Gateway credentials live on the company default settings, not an outlet override.");
    }
    this.provider = Objects.requireNonNull(provider, "provider");
    this.providerEnvironment = Objects.requireNonNull(environment, "environment");
    if (serverKey != null && !serverKey.isBlank()) {
      String stripped = serverKey.strip();
      this.serverKey = stripped;
      this.serverKeyLast4 =
          stripped.length() <= 4 ? stripped : stripped.substring(stripped.length() - 4);
    }
    if (clientKey != null && !clientKey.isBlank()) {
      this.clientKey = clientKey.strip();
    }
  }

  /** Removes any stored gateway credentials (and the provider/environment selection). */
  public void clearGatewayCredentials() {
    this.provider = null;
    this.providerEnvironment = null;
    this.serverKey = null;
    this.clientKey = null;
    this.serverKeyLast4 = null;
  }

  /**
   * Attaches (or replaces) the static QRIS image. By the time this runs, {@link
   * QrImageContentTypeValidator} has already confirmed the declared content type matches the actual
   * magic bytes — the declared header alone is never trusted.
   *
   * @throws IllegalArgumentException if {@code data} is empty or exceeds {@link
   *     #MAX_QR_IMAGE_BYTES}
   */
  public void attachStaticQr(String contentType, byte[] data, String sha256) {
    Objects.requireNonNull(data, "data");
    if (data.length == 0 || data.length > MAX_QR_IMAGE_BYTES) {
      throw new IllegalArgumentException(
          "QRIS image must be between 1 and " + MAX_QR_IMAGE_BYTES + " bytes, was " + data.length);
    }
    this.staticQrContentType = requireNonBlank(contentType, "contentType");
    this.staticQrData = data.clone();
    this.staticQrByteSize = data.length;
    this.staticQrSha256 = requireNonBlank(sha256, "sha256");
  }

  /** Removes the static QRIS image (all four columns together — the V2 CHECK's invariant). */
  public void removeStaticQr() {
    this.staticQrContentType = null;
    this.staticQrData = null;
    this.staticQrByteSize = null;
    this.staticQrSha256 = null;
  }

  public boolean hasStaticQr() {
    return staticQrData != null;
  }

  public boolean hasServerKey() {
    return serverKey != null;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOutletId() {
    return outletId;
  }

  public QrisMode getMode() {
    return mode;
  }

  public String getStaticQrContentType() {
    return staticQrContentType;
  }

  public Integer getStaticQrByteSize() {
    return staticQrByteSize;
  }

  public String getStaticQrSha256() {
    return staticQrSha256 == null ? null : staticQrSha256.strip();
  }

  public PspProvider getProvider() {
    return provider;
  }

  public ProviderEnvironment getProviderEnvironment() {
    return providerEnvironment;
  }

  /**
   * The decrypted server key — writer/charge-path use ONLY (rule 6): never serialized, never
   * logged, never returned by a read endpoint.
   */
  public String getServerKey() {
    return serverKey;
  }

  public String getServerKeyLast4() {
    return serverKeyLast4;
  }

  /** Redacted — no credential, and no image bytes, ever reaches a log line (rule 6). */
  @Override
  public String toString() {
    return "PaymentSettings[id="
        + id
        + ", outletId="
        + outletId
        + ", mode="
        + mode
        + ", hasStaticQr="
        + hasStaticQr()
        + ", provider="
        + provider
        + "]";
  }
}
