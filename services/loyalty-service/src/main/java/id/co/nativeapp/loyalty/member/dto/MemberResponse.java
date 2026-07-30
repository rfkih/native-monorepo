package id.co.nativeapp.loyalty.member.dto;

import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import java.util.UUID;

/**
 * The response shape for enroll / lookup / get-by-id — {@code /api/v1/loyalty/members}.
 *
 * <p><strong>PII decision on lookup responses (documented per the Phase-4 task).</strong> A cashier
 * looking up a walk-in customer by phone needs to CONFIRM the right person before attaching them to
 * a sale — a fully-redacted response ({@code "***REDACTED***"}, employee-service's NIK-severity
 * treatment) would make that impossible with a common name. So this response returns:
 *
 * <ul>
 *   <li>{@code displayName} — PLAIN (not masked). A display name is materially less sensitive than
 *       a national id or bank account (employee-service's redact-fully cases); showing it is the
 *       whole point of a confirmation UI ("Is this Budi Santoso?"), and it is entered by the tenant
 *       itself (not a government identifier).
 *   <li>{@code phoneTail} — masked to the last 4 digits ({@code "****7890"}), never the full phone.
 *       The tail is enough to disambiguate two members who happen to share a display name, without
 *       exposing the searchable/callable full number to every POS terminal.
 *   <li>{@code pointsBalance} — the member's current balance, needed for the redemption UI.
 * </ul>
 *
 * <p>The raw phone and the raw display name NEVER leave the {@link LoyaltyMember} aggregate as
 * plaintext beyond this response shape; they are never logged, never placed on an event (rule 6).
 */
public record MemberResponse(UUID id, String displayName, String phoneTail, long pointsBalance) {

  public static MemberResponse from(LoyaltyMember member) {
    return new MemberResponse(
        member.getId(),
        member.getDisplayName(),
        member.maskedPhoneTail(),
        member.getPointsBalance());
  }
}
