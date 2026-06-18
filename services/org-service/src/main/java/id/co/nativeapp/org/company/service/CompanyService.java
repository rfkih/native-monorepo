package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creates companies (bootstrapping a new tenant) and adds businesses — the M1.2 core.
 *
 * <p>The actual transactional units of work live in {@link CompanyWriter}; this service
 * orchestrates them and owns the <em>tenant-bootstrap</em> contract.
 *
 * <p><strong>The tenant-bootstrap nuance.</strong> Creating a company CREATES a new tenant, so
 * there is no existing {@code app.current_tenant} when the request arrives (the create-company
 * endpoint is exempt from the request-edge tenant filter — see {@link
 * id.co.nativeapp.org.config.DevTenantFilter}). This service therefore:
 *
 * <ol>
 *   <li>generates the new {@code company_id} (a UUID) server-side — never trusts a body/header for
 *       it (rule 5); then
 *   <li>runs the whole bootstrap insert (company + first org_unit + outbox row) INSIDE a {@link
 *       TenantContext#callAs(String, String, java.util.concurrent.Callable)} scope bound to that
 *       NEW {@code company_id} and the request-edge actor.
 * </ol>
 *
 * <p>Because {@link CompanyWriter#create} is {@code @Transactional} and invoked through the Spring
 * proxy from within that scope, the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant = newCompanyId} on the transaction's connection. Every inserted row carries
 * {@code company_id = newCompanyId}, so the RLS {@code WITH CHECK} passes on the bootstrap insert
 * and {@code company_id} is stamped correctly. The actor flows into {@code created_by}/{@code
 * updated_by} via {@code TenantAuditorAware}.
 *
 * <p>This service is deliberately NOT {@code @Transactional} itself — it merely opens the tenant
 * scope and delegates; the transaction boundary lives on the writer so the proxy + RLS aspect
 * engage.
 */
@Service
public class CompanyService {

  private final CompanyWriter writer;

  public CompanyService(CompanyWriter writer) {
    this.writer = writer;
  }

  /**
   * Creates a company (bootstrapping a new tenant) plus its first business, and emits exactly one
   * {@code CompanyCreated} to the outbox — all in one transaction.
   *
   * @param command the create-company command (carries the request-edge actor)
   * @return the new company + first business
   */
  public CreateCompanyResult createCompany(CreateCompanyCommand command) {
    // The new tenant id: generated here, never taken from the request (rule 5).
    UUID newCompanyId = UUID.randomUUID();

    try {
      // Bind the NEW tenant id (and the request-edge actor) for the bootstrap insert,
      // so the auto-RLS aspect sets app.current_tenant = newCompanyId and the WITH
      // CHECK passes. callAs declares checked Exception; the writer only throws
      // unchecked, so the catch below just rethrows.
      return TenantContext.callAs(
          newCompanyId.toString(),
          command.actor(),
          () ->
              writer.create(
                  newCompanyId,
                  command.name(),
                  command.baseCurrency(),
                  command.defaultLanguage(),
                  command.businessName(),
                  command.businessType()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // Unreachable: writer.create throws only unchecked exceptions.
      throw new IllegalStateException("create-company failed", e);
    }
  }

  /**
   * Adds a business (org unit) under an existing company. The tenant scope is already bound at the
   * request edge for this tenant-scoped endpoint; this just delegates to the transactional writer.
   *
   * @param command the create-business command
   * @return the org unit just added
   */
  public OrgUnit addBusiness(CreateBusinessCommand command) {
    // Fail loudly here if no tenant is bound, rather than deep inside the transaction.
    TenantContext.require();
    return writer.addBusiness(command);
  }

  /**
   * All org units visible to the bound tenant. Deliberately unfiltered in code: the result set is
   * constrained solely by the auto-applied RLS policy (no {@code WHERE company_id}). This is the
   * read path the cross-tenant isolation test relies on. Returns a native projection ({@link
   * OrgUnitView}) — narrower I/O than {@code SELECT *} of the entity.
   */
  public List<OrgUnitView> findOrgUnitsForCurrentTenant() {
    return writer.findOrgUnitsForCurrentTenant();
  }

  /**
   * All companies visible to the bound tenant (RLS-constrained; a company is its own tenant).
   * Returns a native projection ({@link CompanyView}) — narrower I/O than {@code SELECT *} of the
   * entity.
   */
  public List<CompanyView> findCompaniesForCurrentTenant() {
    return writer.findCompaniesForCurrentTenant();
  }
}
