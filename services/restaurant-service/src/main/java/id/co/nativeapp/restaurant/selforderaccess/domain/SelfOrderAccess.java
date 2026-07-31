package id.co.nativeapp.restaurant.selforderaccess.domain;

import id.co.nativeapp.restaurant.config.SelfOrderSecretConverter;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code self_order_access} aggregate — one outlet's currently-signing (or historically
 * RETIRED) QR secret (Phase 6, ADR 0029).
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code self_order_access} RLS policy
 * in {@code V19__self_order.sql} (rule 5), which also enforces at most one {@code ACTIVE} row per
 * {@code (company_id, outlet_id)} via a partial unique index — {@link
 * id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessWriter} owns the
 * retire-then-insert rotation sequence.
 *
 * <p>{@code secretPlaintext} is column-encrypted at rest via {@link SelfOrderSecretConverter}
 * (AES-256-GCM, rule 6-adjacent — not personal data, but a signing secret that must never be
 * recoverable from a DB dump). It is NEVER returned by any API response after mint/rotate; only the
 * MINTED TOKENS (already HMAC-signed with it) are.
 */
@Entity
@Table(name = "self_order_access")
public class SelfOrderAccess extends Auditable {

  /** {@code kid} is a compact, printable identifier — never a token/secret; the DB column caps it. */
  private static final int MAX_KID_LENGTH = 16;

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_RETIRED = "RETIRED";

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "outlet_id", nullable = false, updatable = false)
  private UUID outletId;

  @Column(name = "kid", nullable = false, updatable = false, length = MAX_KID_LENGTH)
  private String kid;

  @Convert(converter = SelfOrderSecretConverter.class)
  @Column(name = "secret_encrypted", nullable = false, updatable = false)
  private String secretPlaintext;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  protected SelfOrderAccess() {
    // for JPA
  }

  /**
   * Mints a fresh ACTIVE access row.
   *
   * @param outletId the outlet this secret signs tokens for
   * @param kid a short (&le; 16 char), unique-per-(company, outlet) identifier for this secret —
   *     carried in every token it signs so the verifier knows WHICH row to check
   * @param secretPlaintext the base64-encoded raw HMAC key (never logged; encrypted at rest)
   */
  public SelfOrderAccess(UUID outletId, String kid, String secretPlaintext) {
    this.id = UUID.randomUUID();
    this.outletId = Objects.requireNonNull(outletId, "outletId");
    this.kid = Objects.requireNonNull(kid, "kid");
    if (kid.isBlank() || kid.length() > MAX_KID_LENGTH) {
      throw new IllegalArgumentException(
          "kid must be non-blank and at most " + MAX_KID_LENGTH + " characters");
    }
    this.secretPlaintext = Objects.requireNonNull(secretPlaintext, "secretPlaintext");
    this.status = STATUS_ACTIVE;
  }

  /**
   * Retires this row — every token signed with its secret stops verifying from this moment on (the
   * verifier only matches an {@code ACTIVE} row by {@code (outlet_id, kid)}).
   *
   * @throws IllegalStateException if already RETIRED
   */
  public void retire() {
    if (!STATUS_ACTIVE.equals(status)) {
      throw new IllegalStateException("SelfOrderAccess " + id + " is already RETIRED");
    }
    this.status = STATUS_RETIRED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOutletId() {
    return outletId;
  }

  public String getKid() {
    return kid;
  }

  /** The raw (decrypted) base64-encoded HMAC key — never logged, never returned by any API. */
  public String getSecretPlaintext() {
    return secretPlaintext;
  }

  public String getStatus() {
    return status;
  }

  public boolean isActive() {
    return STATUS_ACTIVE.equals(status);
  }
}
