package id.co.nativeapp.entitlement.entitlement;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code tenant_entitlement} aggregate — one row per {@code (company_id, module_key)} recording
 * whether a company currently has a module ({@link EntitlementStatus#ENTITLED}) or has had it taken
 * away ({@link EntitlementStatus#REVOKED}).
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code tenant_entitlement} RLS policy in the Flyway baseline
 * (rule 4 + rule 5).
 *
 * <p><strong>Owns its grant/revoke invariants.</strong> Construction starts a freshly-{@code
 * ENTITLED} row with {@code granted_at} stamped and {@code revoked_at} null; {@link
 * #revoke(Instant)} flips it to {@code REVOKED} and stamps {@code revoked_at}; {@link
 * #grant(Instant)} re-entitles it (a re-grant after a revoke) and re-stamps {@code granted_at},
 * clearing {@code revoked_at}. The row is never deleted, so the {@code (company_id, module_key)}
 * unique key and the history survive across grant→revoke→re-grant cycles. {@link #isEntitled()} is
 * the single predicate the entitlement-check loader reads.
 */
@Entity
@Table(name = "tenant_entitlement")
public class TenantEntitlement extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "module_key", nullable = false, updatable = false, length = 64)
  private String moduleKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private EntitlementStatus status;

  @Column(name = "granted_at", nullable = false)
  private Instant grantedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected TenantEntitlement() {
    // for JPA
  }

  /**
   * Creates a freshly-{@code ENTITLED} entitlement for a module.
   *
   * @param moduleKey the module key (validated against {@code module_catalog} at the service layer)
   * @param grantedAt when the grant takes effect (UTC)
   */
  public TenantEntitlement(String moduleKey, Instant grantedAt) {
    this.id = UUID.randomUUID();
    this.moduleKey = Objects.requireNonNull(moduleKey, "moduleKey");
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.status = EntitlementStatus.ENTITLED;
    this.revokedAt = null;
  }

  /**
   * (Re-)grants the module: sets the status to {@code ENTITLED}, re-stamps {@code granted_at}, and
   * clears {@code revoked_at}. Idempotent in effect when already entitled (it simply re-stamps).
   *
   * @param grantedAt when the (re-)grant takes effect (UTC)
   */
  public void grant(Instant grantedAt) {
    this.status = EntitlementStatus.ENTITLED;
    this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
    this.revokedAt = null;
  }

  /**
   * Revokes the module: sets the status to {@code REVOKED} and stamps {@code revoked_at}.
   * Idempotent in effect when already revoked (it re-stamps the revoke time).
   *
   * @param revokedAt when the revoke takes effect (UTC)
   */
  public void revoke(Instant revokedAt) {
    this.status = EntitlementStatus.REVOKED;
    this.revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
  }

  /** {@code true} if the company currently has this module (status {@code ENTITLED}). */
  public boolean isEntitled() {
    return status == EntitlementStatus.ENTITLED;
  }

  public UUID getId() {
    return id;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public EntitlementStatus getStatus() {
    return status;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
