package id.co.nativeapp.org.group.dto;

import java.util.UUID;

/**
 * Flat list-item DTO for {@code GET /api/v1/consolidation-groups}. Contains only the fields the
 * console's group-consolidation page needs to list and select a group (no {@code Auditable}
 * bookkeeping). Mapped from the native-query projection in the service layer — never directly from
 * an entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param id the group id
 * @param name the group name
 * @param leadCompanyId the lead/owner company (also the group's tenant)
 * @param reportingCurrency the immutable ISO-4217 reporting currency (CHAR-padded value stripped)
 */
public record ConsolidationGroupListResponse(
    UUID id, String name, UUID leadCompanyId, String reportingCurrency) {}
