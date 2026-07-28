package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.OrgUnitListResponse;
import id.co.nativeapp.org.company.dto.OutletResponse;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Creates and patches org-tree nodes for the bound company — the full-tree counterpart to {@link
 * CompanyService}. It orchestrates the transactional units of work in {@link OrgUnitWriter}; the
 * transaction boundary, RLS GUC, and outbox write all live on the writer so the Spring proxy + the
 * auto-RLS aspect engage (the {@code *Writer} pattern, ENGINEERING-STANDARDS §2.5).
 *
 * <p>Both endpoints are tenant-scoped (the tenant is bound at the request edge), so this service
 * only asserts a tenant is present and delegates; {@code company_id} is stamped by the writer from
 * {@link TenantContext}, never from the request (rule 5).
 */
@Service
public class OrgUnitService {

  private final OrgUnitWriter writer;
  private final OrgUnitReader reader;

  public OrgUnitService(OrgUnitWriter writer, OrgUnitReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Creates an org unit under the bound company (validated hierarchy) and emits {@code
   * OrgUnitCreated}.
   *
   * @param command the create command
   * @return the persisted org unit
   */
  public OrgUnit create(CreateOrgUnitCommand command) {
    TenantContext.require();
    return writer.create(command);
  }

  /**
   * Patches an org unit (rename / move / deactivate) and emits {@code OrgUnitChanged} per change.
   *
   * @param command the patch command
   * @return the org unit in its post-change state
   */
  public OrgUnit patch(PatchOrgUnitCommand command) {
    TenantContext.require();
    return writer.patch(command);
  }

  /**
   * All org units visible to the bound tenant, as a flat list ordered parent-before-child. No
   * {@code WHERE company_id} — RLS scopes the result (rule 5). Projection-to-DTO mapping happens
   * here in the service layer (projection is not exposed to the controller — CODE-STRUCTURE §3.3).
   *
   * @return the flat org-unit list for the current tenant
   */
  public List<OrgUnitListResponse> findAllForCurrentTenant() {
    TenantContext.require();
    return reader.findAllForCurrentTenant().stream()
        .map(
            v ->
                new OrgUnitListResponse(
                    v.getId(),
                    v.getName(),
                    v.getType(),
                    v.getVertical(),
                    v.getParentId(),
                    v.isActive()))
        .toList();
  }

  /**
   * Active OUTLET nodes visible to the bound tenant, ordered by name. No {@code WHERE company_id} —
   * RLS scopes the result (rule 5). Backs the POS outlet picker ({@code GET /api/v1/outlets}).
   * Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
   *
   * @return active outlets for the current tenant, ordered by name; empty list when none exist
   */
  public List<OutletResponse> listActiveOutlets() {
    TenantContext.require();
    return reader.findActiveOutletsForCurrentTenant().stream()
        .map(v -> new OutletResponse(v.getId(), v.getName(), v.getVertical()))
        .toList();
  }
}
