package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.org.company.domain.OrgUnit;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Org-unit response body — the node just created or patched. Exposes the full tree-relevant state
 * so the onboarding wizard / console can render the hierarchy without a second call.
 *
 * @param id the org-unit id
 * @param name the display name
 * @param type the org-unit type (e.g. {@code "OUTLET"})
 * @param vertical the business vertical (lowercase {@code restaurant} | {@code carwash} | {@code
 *     barbershop}) — non-null only for a {@code BUSINESS_UNIT}
 * @param parentId the parent org-unit id, or {@code null} for a top-level node
 * @param legalEmployerId the legal employer this node belongs to
 * @param companyId the owning tenant
 * @param active whether the node is currently active
 * @param effectiveFrom the date the node became effective
 * @param effectiveTo the date the node ceases to be effective ({@code 9999-12-31} when open-ended)
 */
public record OrgUnitResponse(
    UUID id,
    String name,
    String type,
    String vertical,
    UUID parentId,
    UUID legalEmployerId,
    UUID companyId,
    boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  public static OrgUnitResponse from(OrgUnit orgUnit) {
    return new OrgUnitResponse(
        orgUnit.getId(),
        orgUnit.getName(),
        orgUnit.getType().name(),
        null, // ADR 0070: the vertical lives on the company now; kept for wire compat
        orgUnit.getParentId(),
        orgUnit.getLegalEmployerId(),
        UUID.fromString(orgUnit.getCompanyId()),
        orgUnit.isActive(),
        orgUnit.getEffectiveFrom(),
        orgUnit.getEffectiveTo());
  }
}
