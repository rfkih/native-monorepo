package id.co.nativeapp.org.group.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat list-item DTO for {@code GET /api/v1/consolidation-groups/{groupId}/members}. Contains only
 * the fields the console's group-membership list needs to render the active/closed state and the
 * effective dates window. Mapped from the native-query projection in the service layer — never
 * directly from an entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param memberCompanyId the member company id
 * @param effectiveFrom the date the membership became effective
 * @param effectiveTo the date the membership ceases to be effective ({@code 9999-12-31} when
 *     open/active — the open-ended sentinel from {@code GroupMembership.OPEN_ENDED})
 * @param active whether the membership window is currently open (effectiveTo is the sentinel)
 */
public record GroupMembershipListResponse(
    UUID memberCompanyId, LocalDate effectiveFrom, LocalDate effectiveTo, boolean active) {}
