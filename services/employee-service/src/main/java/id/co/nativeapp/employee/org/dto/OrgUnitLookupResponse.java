package id.co.nativeapp.employee.org.dto;

import java.util.UUID;

/**
 * Response item for {@code GET /api/v1/employees/org-units?ids=} — the local org-read-model lookup
 * the console uses to resolve an org unit's legal employer before creating an employment contract
 * (rule 2: never a synchronous call to org-service). A requested id the projection has not seen —
 * or another tenant's, invisible under RLS — is silently absent from the result.
 *
 * @param orgUnitId the org-unit id
 * @param legalEmployerId the unit's legal employer (what an employment contract references)
 * @param type the org-unit kind (business_unit | outlet | team — lowercase, ADR 0012)
 * @param active whether the unit is active
 */
public record OrgUnitLookupResponse(
    UUID orgUnitId, UUID legalEmployerId, String type, boolean active) {}
