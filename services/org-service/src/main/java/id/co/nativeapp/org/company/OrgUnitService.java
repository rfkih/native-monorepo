package id.co.nativeapp.org.company;

import id.co.nativeapp.tenant.TenantContext;
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

  public OrgUnitService(OrgUnitWriter writer) {
    this.writer = writer;
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
}
