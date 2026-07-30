package id.co.nativeapp.loyalty.member.domain;

import id.co.nativeapp.loyalty.config.PiiBytesAttributeConverter;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code loyalty_member} aggregate — a per-company customer enrolled in the points program.
 * loyalty-service is the SOLE owner of this aggregate and the ONLY home of member PII (rule 6):
 * {@code phone} and {@code displayName} are column-level encrypted ({@link
 * PiiBytesAttributeConverter}, AES-256-GCM); the plaintext NEVER leaves this aggregate as-is to a
 * log, a {@link #toString()}, an event payload ({@code LoyaltyBalanceChanged} carries only the
 * opaque {@code member_id}), or a DTO beyond the deliberate cashier-confirmation affordance (see
 * {@code member.dto.MemberResponse} class javadoc for the PII decision on lookup responses).
 *
 * <p><strong>{@code phoneHash} is a SEPARATE deterministic HMAC-SHA256 digest</strong> (rule 6 —
 * never store/search plaintext PII) of the {@link
 * id.co.nativeapp.loyalty.member.domain.PhoneNormalizer normalized} phone, used for the exact-match
 * {@code UNIQUE(company_id, phone_hash)} lookup — the encrypted column itself can never be searched
 * (randomized IV, see {@code PiiCipher}).
 *
 * <p><strong>{@code pointsBalance} MAY GO NEGATIVE</strong> (flag-on-overdraft policy, ADR 0027): a
 * checkout can accept a points redemption against a vertical's local (eventually-consistent) cached
 * balance that is stale by the time loyalty-service applies the authoritative ledger entry. Rather
 * than reject the already-completed sale or block synchronously (rule 2 forbids a sync call), the
 * ledger applies anyway and a {@code LoyaltyRedemptionFlagged} event is raised for manual
 * follow-up. This is a deliberate, documented trade-off (see the ingest writer), not a bug.
 *
 * <p>{@code balanceSeq} is a per-member monotonically increasing sequence bumped on every
 * balance-affecting write, carried ABSOLUTE (not delta) on {@code LoyaltyBalanceChanged} so a
 * vertical's cached {@code member_balance_ref} applies only a strictly-newer sequence (set-if-newer
 * — ARCHITECTURE.md §3 log-compacted-topic / snapshot hydration discipline).
 */
@Entity
@Table(name = "loyalty_member")
public class LoyaltyMember extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * The member's phone number — PII. Column-encrypted at rest via {@link
   * PiiBytesAttributeConverter}.
   */
  @Convert(converter = PiiBytesAttributeConverter.class)
  @Column(name = "phone_encrypted", nullable = false)
  private String phone;

  /**
   * Deterministic HMAC-SHA256 digest of the normalized phone (see class javadoc) — the exact-match
   * lookup key. NOT reversible to the plaintext phone.
   */
  @Column(name = "phone_hash", nullable = false, length = 64)
  private String phoneHash;

  /** The member's display name — PII, optional. Column-encrypted at rest, may be null. */
  @Convert(converter = PiiBytesAttributeConverter.class)
  @Column(name = "display_name_encrypted")
  private String displayName;

  /** The member's resulting points balance. MAY GO NEGATIVE (see class javadoc). Never a float. */
  @Column(name = "points_balance", nullable = false)
  private long pointsBalance;

  /** Monotonically increasing per-member sequence, bumped on every balance-affecting write. */
  @Column(name = "balance_seq", nullable = false)
  private long balanceSeq;

  protected LoyaltyMember() {
    // for JPA
  }

  /**
   * Enrolls a new member with a freshly generated id and a zero starting balance.
   *
   * @param normalizedPhone the ALREADY-NORMALIZED phone (see {@link PhoneNormalizer}); must be
   *     non-blank
   * @param phoneHash the HMAC-SHA256 digest of {@code normalizedPhone}; must be non-blank
   * @param displayName the member's optional display name; may be {@code null}
   */
  public LoyaltyMember(String normalizedPhone, String phoneHash, String displayName) {
    this.id = UUID.randomUUID();
    this.phone = requireNonBlank(normalizedPhone, "phone");
    this.phoneHash = requireNonBlank(phoneHash, "phoneHash");
    this.displayName = displayName == null ? null : displayName.strip();
    this.pointsBalance = 0L;
    this.balanceSeq = 0L;
  }

  /**
   * Applies a points delta (positive for EARN, negative for REDEEM/REVERSE) and bumps {@link
   * #balanceSeq}. The resulting balance MAY be negative — the caller (the ingest writer) decides
   * whether to raise a {@code LoyaltyRedemptionFlagged} event when that happens; this method itself
   * never rejects an overdraft (rule 2 — no synchronous cross-service block is possible, and the
   * sale has already completed).
   *
   * @return the resulting ABSOLUTE balance, for the caller to stamp onto {@code
   *     LoyaltyBalanceChanged}
   */
  public long applyPointsDelta(long delta) {
    this.pointsBalance = Math.addExact(this.pointsBalance, delta);
    this.balanceSeq = Math.addExact(this.balanceSeq, 1);
    return this.pointsBalance;
  }

  public UUID getId() {
    return id;
  }

  public String getPhoneHash() {
    return phoneHash;
  }

  public long getPointsBalance() {
    return pointsBalance;
  }

  public long getBalanceSeq() {
    return balanceSeq;
  }

  /**
   * The display name for a POS lookup response — returned PLAIN (not masked). See {@code
   * member.dto.MemberResponse} class javadoc for the PII decision: a cashier confirming a walk-in
   * customer's identity needs the name to read naturally, whereas the phone is masked to its last 4
   * digits. May be {@code null} if the member enrolled without one.
   */
  public String getDisplayName() {
    return displayName;
  }

  /**
   * The phone masked to its last 4 digits (e.g. {@code "****7890"}) for a response/DTO. The raw
   * plaintext is NEVER returned (rule 6).
   */
  public String maskedPhoneTail() {
    return PiiMasking.lastFour(phone);
  }

  /**
   * The raw decrypted phone. <strong>Restricted:</strong> intended only for the encrypt/decrypt
   * path and authorized internal use (the phone-hash re-verification test) — it is never placed in
   * a response, log, or event. Package-private so only the feature can reach it.
   */
  String phonePlaintext() {
    return phone;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  /**
   * A PII-safe representation: id / balance only, NEVER the phone or display name (rule 6 — PII
   * never logged). An accidental {@code log.info("member={}", member)} cannot leak PII.
   */
  @Override
  public String toString() {
    return "LoyaltyMember[id=" + id + ", pointsBalance=" + pointsBalance + "]";
  }
}
