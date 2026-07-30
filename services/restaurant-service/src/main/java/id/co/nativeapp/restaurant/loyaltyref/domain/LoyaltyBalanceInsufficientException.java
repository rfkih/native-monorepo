package id.co.nativeapp.restaurant.loyaltyref.domain;

import java.util.UUID;

/**
 * Thrown at checkout when a requested points redemption cannot be atomically decremented from the
 * LOCAL {@code member_balance_ref} cache (ADR 0027, Phase 4) — either because the member is
 * UNKNOWN to this vertical's cache (not yet replicated by {@code LoyaltyBalanceChanged}, or an
 * invalid id), or because a concurrent redemption raced ours between the clamp read and the atomic
 * decrement. Both cases are the same wire problem from the caller's point of view (a redemption
 * that cannot be honoured right now), so they share one exception/response shape — documented
 * per the task's pinned semantics, not a bug.
 *
 * <p>Maps to {@code 409 Conflict} RFC-7807 ({@code loyalty-balance-insufficient}) via {@link
 * id.co.nativeapp.restaurant.config.LoyaltyRefAdvice}. {@code memberId} is an opaque UUID — not PII
 * (rule 6) — safe to log/echo.
 */
public class LoyaltyBalanceInsufficientException extends RuntimeException {

  /** Stable RFC-7807 problem type URI — the UI maps it to an i18n key. */
  public static final String TYPE = "https://errors.nativeapp.id/loyalty-balance-insufficient";

  private final UUID memberId;

  public LoyaltyBalanceInsufficientException(UUID memberId) {
    super(
        "Loyalty member "
            + memberId
            + " is unknown to this outlet's balance cache, or its balance is insufficient for the"
            + " requested redemption");
    this.memberId = memberId;
  }

  public UUID getMemberId() {
    return memberId;
  }
}
