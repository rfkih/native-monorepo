package id.co.nativeapp.org.group;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Group-membership response body — the membership row after an add or remove. Exposes the
 * effective-dated window so the console can render the active/closed state (an open membership
 * carries the {@code 9999-12-31} sentinel as {@code effectiveTo}).
 *
 * @param id the membership id
 * @param groupId the group
 * @param memberCompanyId the member company
 * @param active whether the membership window is currently open
 * @param effectiveFrom the date the membership became effective
 * @param effectiveTo the date the membership ceases to be effective ({@code 9999-12-31} when open)
 */
public record MembershipResponse(
    UUID id,
    UUID groupId,
    UUID memberCompanyId,
    boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  static MembershipResponse from(GroupMembership membership) {
    return new MembershipResponse(
        membership.getId(),
        membership.getGroupId(),
        membership.getMemberCompanyId(),
        membership.isActive(),
        membership.getEffectiveFrom(),
        membership.getEffectiveTo());
  }
}
