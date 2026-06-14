package id.co.nativeapp.org.company;

import java.util.UUID;

/** Create-business response body — the org unit just added under the company. */
public record OrgUnitResponse(UUID id, String name, String type, UUID parentId, UUID companyId) {

  static OrgUnitResponse from(OrgUnit orgUnit) {
    return new OrgUnitResponse(
        orgUnit.getId(),
        orgUnit.getName(),
        orgUnit.getType().name(),
        orgUnit.getParentId(),
        UUID.fromString(orgUnit.getCompanyId()));
  }
}
