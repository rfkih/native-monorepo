package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.org.user.dto.OrgUnitUserResponse;
import id.co.nativeapp.org.user.repository.UserOutletAssignmentRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the active user↔outlet assignments under an org unit — the org-unit hub's People tab
 * ({@code GET /api/v1/org-units/{orgUnitId}/users}).
 *
 * <p>A separate {@code @Component} with {@code @Transactional(readOnly = true)} methods so the tx
 * proxy + auto-RLS aspect engage (the org-service {@code *Reader} convention, mirroring {@code
 * UserOutletAssignmentReader}); neither query carries a {@code WHERE company_id} (rule 5). The
 * target unit is validated first via a narrow projection read: an id RLS makes invisible (foreign
 * tenant) throws the same {@link OrgUnitNotFoundException} as a genuinely unknown one
 * (anti-enumeration), and a TEAM is rejected — assignments target outlets; a business unit
 * aggregates its child outlets.
 *
 * <p>Identity fields are deliberately absent from the response — the console joins
 * username/email/roles from its cached team list, keeping Keycloak out of this path.
 */
@Component
public class OrgUnitUsersReader {

  private final OrgUnitRepository orgUnitRepository;
  private final UserOutletAssignmentRepository assignmentRepository;

  public OrgUnitUsersReader(
      OrgUnitRepository orgUnitRepository, UserOutletAssignmentRepository assignmentRepository) {
    this.orgUnitRepository = Objects.requireNonNull(orgUnitRepository, "orgUnitRepository");
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
  }

  /**
   * Active assignments under {@code orgUnitId}: the outlet's own when the unit is an OUTLET, or all
   * child outlets' when it is a BUSINESS_UNIT. An empty list is a valid answer (200) — a unit
   * nobody is assigned to.
   *
   * @throws OrgUnitNotFoundException when the unit is unknown in the bound tenant (mapped to 404)
   * @throws InvalidUnitUsersTargetException when the unit is a TEAM (mapped to 400)
   */
  @Transactional(readOnly = true)
  public List<OrgUnitUserResponse> usersForUnit(UUID orgUnitId) {
    OrgUnitView unit =
        orgUnitRepository
            .findViewById(orgUnitId)
            .orElseThrow(() -> new OrgUnitNotFoundException(orgUnitId));
    // ADR 0070: the TEAM level is gone, so every org unit is a valid target — an outlet is
    // exactly what an assignment points at. The existence/tenant check above is the whole guard.
    return assignmentRepository.findActiveUnderUnit(orgUnitId).stream()
        .map(v -> new OrgUnitUserResponse(v.getUserId(), v.getOrgUnitId(), v.getOutletName()))
        .toList();
  }
}
