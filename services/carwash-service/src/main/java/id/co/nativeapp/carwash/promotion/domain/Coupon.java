package id.co.nativeapp.carwash.promotion.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A redemption code bound to one {@link PromoRule} (ADR 0026). Maps to the {@code coupon} table
 * (V8__promotions.sql), unique on {@code (company_id, code)} where {@code code} is uppercase-
 * normalized by the service before insert/lookup. Ported verbatim from restaurant-service.
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code coupon_tenant_isolation} RLS policy
 * (rule 5). Redemption is enforced by the ATOMIC {@code CouponRepository#redeemIfAvailable} UPDATE,
 * not by a read-modify-write on this entity — {@link #redeemedCount} here is read-only from the
 * application's point of view outside that UPDATE (a fresh read always reflects the committed
 * count).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "coupon")
public class Coupon extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40)
  private String code;

  @Column(name = "rule_id", nullable = false, updatable = false)
  private UUID ruleId;

  @Column(name = "max_redemptions", nullable = false)
  private int maxRedemptions;

  @Column(name = "redeemed_count", nullable = false)
  private int redeemedCount;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected Coupon() {
    // for JPA
  }

  /**
   * Creates a coupon with a freshly generated id, active by default and zero redemptions.
   *
   * @param code the ALREADY uppercase-normalized code (the service, not this constructor,
   *     normalizes)
   * @param ruleId the linked {@link PromoRule} id (must already exist — validated by the service)
   * @param maxRedemptions must be &gt; 0
   * @param expiresAt optional expiry instant; {@code null} means never expires
   */
  public Coupon(String code, UUID ruleId, int maxRedemptions, Instant expiresAt) {
    if (maxRedemptions <= 0) {
      throw new IllegalArgumentException("maxRedemptions must be > 0, got: " + maxRedemptions);
    }
    this.id = UUID.randomUUID();
    this.code = Objects.requireNonNull(code, "code");
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
    this.maxRedemptions = maxRedemptions;
    this.redeemedCount = 0;
    this.expiresAt = expiresAt;
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public int getMaxRedemptions() {
    return maxRedemptions;
  }

  public void setMaxRedemptions(int maxRedemptions) {
    if (maxRedemptions <= 0) {
      throw new IllegalArgumentException("maxRedemptions must be > 0, got: " + maxRedemptions);
    }
    this.maxRedemptions = maxRedemptions;
  }

  public int getRedeemedCount() {
    return redeemedCount;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
