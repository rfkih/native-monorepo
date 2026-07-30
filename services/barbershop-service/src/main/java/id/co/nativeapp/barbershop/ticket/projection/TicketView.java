package id.co.nativeapp.barbershop.ticket.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over a {@code barbershop_ticket} row (joined to {@code staff_profile} for the
 * barber's display label) — only the columns a {@code TicketResponse} needs, never the {@code
 * Auditable} bookkeeping (CODE-STRUCTURE §3.3).
 *
 * <p>The {@code currency} column is {@code CHAR(3)} in PostgreSQL and may carry trailing spaces;
 * callers must {@link String#strip()} it when building a response.
 */
public interface TicketView {

  UUID getId();

  UUID getBusinessId();

  String getChair();

  UUID getStaffProfileId();

  /**
   * The barber's {@code display_label}, LEFT JOINed from {@code staff_profile}; {@code null} if the
   * linked row no longer exists (the {@code staff_profile_id} FK is unenforced, matching carwash).
   */
  String getStaffLabel();

  UUID getSaleId();

  long getSubtotalMinor();

  long getDiscountMinor();

  long getServiceChargeMinor();

  long getTaxMinor();

  long getTotalMinor();

  String getCurrency();

  String getTaxRuleVersion();

  boolean isUsesIllustrativeRules();

  Instant getOccurredAt();

  String getIdempotencyKey();

  /** Phase 4 (ADR 0027): the attached loyalty member, or {@code null}. */
  UUID getLoyaltyMemberId();

  /** Phase 4 (ADR 0027): the ACTUAL points redeemed, or {@code null}. */
  Long getLoyaltyRedeemedPoints();

  /**
   * Phase 4 (ADR 0027): the currency value of the redeemed points, minor units, or {@code null}.
   */
  Long getLoyaltyRedeemedMinor();

  /** Phase 4 (ADR 0027): the redeemed gift card, or {@code null}. */
  UUID getGiftCardId();

  /**
   * Phase 4 (ADR 0027): the ACTUAL amount redeemed from the gift card, minor units, or {@code
   * null}.
   */
  Long getGiftCardRedeemedMinor();
}
