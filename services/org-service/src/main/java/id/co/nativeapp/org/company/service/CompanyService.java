package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
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
  private final CompanyReader reader;
  private final PlanTierWriter planTierWriter;

  public CompanyService(CompanyWriter writer, CompanyReader reader, PlanTierWriter planTierWriter) {
    this.writer = writer;
    this.reader = reader;
    this.planTierWriter = planTierWriter;
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
    return createCompany(command, UUID.randomUUID());
  }

  /**
   * Creates a company under a caller-supplied tenant id — used by the signup orchestration, which
   * generates the id up front so the owner's Keycloak user can be created (with its {@code
   * company_id} attribute) BEFORE the company row exists, keeping the compensation path a single
   * Keycloak user delete.
   *
   * <p>Rule 5 still holds: {@code newCompanyId} is always generated <em>server-side</em> (here or
   * in {@link id.co.nativeapp.org.user.service.SignupService}) — it never originates from a request
   * body or header.
   *
   * @param command the create-company command (carries the request-edge actor)
   * @param newCompanyId the server-generated tenant id to bootstrap under
   * @return the new company + first business
   */
  public CreateCompanyResult createCompany(CreateCompanyCommand command, UUID newCompanyId) {
    try {
      // Bind the NEW tenant id (and the request-edge actor) for the bootstrap insert,
      // so the auto-RLS aspect sets app.current_tenant = newCompanyId and the WITH
      // CHECK passes. callAs declares checked Exception; the writer only throws
      // unchecked, so the catch below just rethrows.
      return TenantContext.callAs(
          newCompanyId.toString(), command.actor(), () -> writer.create(newCompanyId, command));
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

  /**
   * The company for the currently bound tenant, together with its first business id — the data the
   * console SPA needs immediately after OIDC login.
   *
   * <p>The company id comes exclusively from the bound {@link TenantContext} (rule 5); the client
   * never supplies it. RLS scopes the read to the bound company. The first business is the
   * earliest-created root {@code BUSINESS_UNIT} org unit, matching the selection the create-company
   * bootstrap uses.
   *
   * @return the company response for the bound tenant
   * @throws NoSuchElementException if no company exists for the bound tenant (→ 404)
   */
  public CompanyResponse getCurrentCompany() {
    TenantContext.require();
    return reader.findCurrentCompany();
  }

  /**
   * Changes the bound tenant's plan tier (ADR 0044 — "Simple mode" for UMKM), owner-only. Delegates
   * the mutation to {@link PlanTierWriter} (role check, whitelist validation, the transactional
   * update) and then re-reads the company via {@link CompanyReader} so the response carries the
   * post-change state, including {@code firstBusinessId} (which {@link PlanTierWriter} does not
   * resolve).
   *
   * @param planTier the requested tier ({@code FREE} or {@code FULL}, any case, trimmed)
   * @return the updated company response for the bound tenant
   * @throws PlanTierForbiddenException if the caller is not an owner (→ 403)
   * @throws InvalidPlanTierException if {@code planTier} is not whitelisted (→ 422)
   */
  public CompanyResponse changePlanTier(String planTier) {
    TenantContext.require();
    planTierWriter.changePlanTier(planTier);
    return reader.findCurrentCompany();
  }

  /**
   * The companies a login belongs to — the {@code /mine} read for the company switcher
   * (multi-company ownership, ADR 0021). {@code companyIds} is the caller's TOKEN-VERIFIED
   * membership set (never client input): for each id a tenant scope is opened over that id (each
   * company is the caller's own — the same trust base as the active-company binding) and the
   * company profile read under RLS. An id whose company row does not exist (the dangling-membership
   * double-failure residual) is skipped rather than failing the whole list. Order preserved (first
   * = the token's default active company).
   *
   * @param companyIds the caller's verified membership set (from the JWT claim)
   * @param actor the caller's principal (the JWT {@code sub})
   * @return the caller's company profiles, token order, unknown ids skipped
   */
  public List<CompanyResponse> findMyCompanies(List<String> companyIds, String actor) {
    List<CompanyResponse> companies = new java.util.ArrayList<>(companyIds.size());
    for (String companyId : companyIds) {
      try {
        companies.add(TenantContext.callAs(companyId, actor, reader::findCurrentCompany));
      } catch (NoSuchElementException dangling) {
        // A membership pointing at a company that does not exist (a failed create whose
        // compensation also failed) — skip it; the log line in the compensation path covers it.
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        // callAs declares checked Exception; the reader only throws unchecked — unreachable.
        throw new IllegalStateException("my-companies read failed", e);
      }
    }
    return companies;
  }
}
