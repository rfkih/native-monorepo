package id.co.nativeapp.org.user.dto;

import java.util.UUID;

/**
 * Response item for {@code GET /api/v1/users/{userId}/outlets} and {@code GET
 * /api/v1/users/me/outlets}.
 *
 * <p>Contains only the fields the POS outlet picker and the Team page outlet editor need: the
 * outlet's UUID (for selection) and its display name. Mapped from the {@link
 * id.co.nativeapp.org.user.projection.UserOutletAssignmentView} projection in the service layer —
 * never directly from the entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param orgUnitId the outlet's org-unit UUID
 * @param name the outlet's display name
 */
public record UserOutletAssignmentResponse(UUID orgUnitId, String name) {}
