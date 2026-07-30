package id.co.nativeapp.loyalty.giftcard.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code gift_card} aggregate — a per-company stored-value card, created from a vertical's
 * {@code GiftCardSold} event. The card's {@code id} is NOT generated here: it is minted by the
 * selling vertical (the till) and carried on the event, so the same id names the card across
 * services (rule 1 — no cross-service joins, but a shared opaque id is fine).
 *
 * <p><strong>{@code balanceMinor} MAY GO NEGATIVE</strong> (flag-on-overdraft policy, ADR 0027) —
 * the exact same eventual-consistency trade-off {@link
 * id.co.nativeapp.loyalty.member.domain.LoyaltyMember#applyPointsDelta} documents for points: a
 * checkout can accept a redemption against a vertical's stale local {@code gift_card_ref} cache.
 *
 * <p>{@code balanceSeq} is bumped on every balance-affecting write, carried ABSOLUTE on {@code
 * GiftCardStateChanged} (set-if-newer discipline, ARCHITECTURE.md §3).
 */
@Entity
@Table(name = "gift_card")
public class GiftCard extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Derived deterministically from {@link #id} — see {@link GiftCardCodeGenerator}. Not PII. */
  @Column(name = "code", nullable = false, length = 24)
  private String code;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private GiftCardState state;

  /** MAY GO NEGATIVE (see class javadoc). Never a float (rule 8). */
  @Column(name = "balance_minor", nullable = false)
  private long balanceMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "balance_seq", nullable = false)
  private long balanceSeq;

  @Column(name = "sold_at", nullable = false)
  private Instant soldAt;

  @Column(name = "sold_business_id", nullable = false)
  private UUID soldBusinessId;

  @Column(name = "expires_at")
  private Instant expiresAt;

  protected GiftCard() {
    // for JPA
  }

  /** Creates a newly-sold card at its initial ACTIVE state with {@code amountMinor} balance. */
  public GiftCard(
      UUID id, String currency, long amountMinor, Instant soldAt, UUID soldBusinessId) {
    this.id = Objects.requireNonNull(id, "id");
    this.code = GiftCardCodeGenerator.deriveCode(id);
    this.state = GiftCardState.ACTIVE;
    this.balanceMinor = amountMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.balanceSeq = 0L;
    this.soldAt = Objects.requireNonNull(soldAt, "soldAt");
    this.soldBusinessId = Objects.requireNonNull(soldBusinessId, "soldBusinessId");
  }

  /** Adds stored value (a LOAD/top-up). The card stays/returns to ACTIVE. Bumps {@link #balanceSeq}. */
  public long load(long amountMinor) {
    this.balanceMinor = Math.addExact(this.balanceMinor, amountMinor);
    if (this.state == GiftCardState.DEPLETED) {
      this.state = GiftCardState.ACTIVE;
    }
    this.balanceSeq = Math.addExact(this.balanceSeq, 1);
    return this.balanceMinor;
  }

  /**
   * Applies a signed delta (negative for a REDEEM, positive for a REVERSE giving value back).
   * Transitions to DEPLETED exactly when the resulting balance is zero; stays/returns to ACTIVE
   * otherwise (per ADR 0027 — even a negative balance stays ACTIVE, not DEPLETED, so the flagged
   * overdraft state is visually distinguishable from an intentionally-exhausted card).
   *
   * @return the resulting ABSOLUTE balance
   */
  public long applyBalanceDelta(long deltaMinor) {
    this.balanceMinor = Math.addExact(this.balanceMinor, deltaMinor);
    if (this.balanceMinor == 0L) {
      this.state = GiftCardState.DEPLETED;
    } else if (this.state == GiftCardState.DEPLETED) {
      this.state = GiftCardState.ACTIVE;
    }
    this.balanceSeq = Math.addExact(this.balanceSeq, 1);
    return this.balanceMinor;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public GiftCardState getState() {
    return state;
  }

  public long getBalanceMinor() {
    return balanceMinor;
  }

  public String getCurrency() {
    return currency.strip();
  }

  public long getBalanceSeq() {
    return balanceSeq;
  }

  public Instant getSoldAt() {
    return soldAt;
  }

  public UUID getSoldBusinessId() {
    return soldBusinessId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
