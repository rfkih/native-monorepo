package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * Flat list-item DTO for {@code GET /api/v1/org-units}. Contains only the fields the console's org
 * tree page needs to assemble and render the hierarchy (no {@code Auditable} bookkeeping, no {@code
 * legalEmployerId} — the tree page does not need it). The frontend builds the tree from this flat
 * list keyed on {@code id} and {@code parentId}.
 *
 * <p>Mapped from the native-query projection in the service layer — never directly from an entity
 * (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param id the org-unit id
 * @param name the display name
 * @param type the org-unit type string ({@code BUSINESS_UNIT}, {@code BRANCH}, {@code OUTLET},
 *     {@code TEAM})
 * @param parentId the parent org-unit id, or {@code null} for a top-level node
 * @param active whether the node is currently active
 */
public record OrgUnitListResponse(
    UUID id, String name, String type, UUID parentId, boolean active) {}
