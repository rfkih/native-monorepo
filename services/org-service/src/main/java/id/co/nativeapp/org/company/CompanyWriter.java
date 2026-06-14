package id.co.nativeapp.org.company;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work behind {@link CompanyService}.
 *
 * <p>It is a distinct bean (not private methods on {@code CompanyService}) so each transactional
 * method is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC, which
 * is load-bearing here: the whole point of the create-company flow is that the auto-RLS aspect sets
 * {@code app.current_tenant} to the NEW tenant id so the RLS {@code WITH CHECK} passes on the
 * bootstrap insert.
 */
@Component
public class CompanyWriter {

  private final CompanyRepository companyRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OutboxWriter outboxWriter;

  public CompanyWriter(
      CompanyRepository companyRepository,
      OrgUnitRepository orgUnitRepository,
      OutboxWriter outboxWriter) {
    this.companyRepository = companyRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.outboxWriter = outboxWriter;
  }

  /**
   * Bootstraps a new tenant in ONE transaction: persists the {@link Company}, its first {@link
   * OrgUnit} (the business), and the {@code CompanyCreated} outbox row — all stamped with the new
   * {@code companyId} and all atomic (rule 3; a rollback drops every row).
   *
   * <p><strong>Tenant bootstrap.</strong> This method MUST be called inside a {@link
   * TenantContext#callAs(String, String, java.util.concurrent.Callable)} scope bound to {@code
   * newCompanyId} (see {@link CompanyService#createCompany}). Because it is {@code @Transactional}
   * and invoked through the proxy, {@link RlsAutoApplyAspect} sets {@code app.current_tenant =
   * newCompanyId} on this transaction's connection before the body runs. Every row written here
   * carries {@code company_id = newCompanyId}, so the RLS policy's {@code WITH CHECK} (which
   * requires {@code company_id = current_setting('app.current_tenant', true)}) passes — even
   * though, before this flow, no such tenant existed. A company is its own tenant: {@code
   * company.id == company_id == newCompanyId}.
   *
   * <p>{@code REQUIRES_NEW} guarantees its own transaction even though {@link
   * CompanyService#createCompany} is not transactional.
   *
   * @param newCompanyId the freshly generated company id == new tenant id (bound in the scope)
   * @param name the company name
   * @param baseCurrency the ISO-4217 base currency (validated + made immutable by {@link Company})
   * @param defaultLanguage the company default language
   * @param businessName the first business (org-unit) name
   * @param businessType the first business (org-unit) type
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CreateCompanyResult create(
      UUID newCompanyId,
      String name,
      String baseCurrency,
      String defaultLanguage,
      String businessName,
      String businessType) {

    // The tenant the auto-RLS aspect has bound to this transaction; it MUST equal the
    // new company id, so the company is its own tenant and the WITH CHECK passes.
    String tenant = TenantContext.require().companyId();

    // A company is its own legal employer in M1.2 (one company == one legal employer);
    // the dedicated legal_employer aggregate arrives with the full org tree later.
    UUID legalEmployerId = newCompanyId;

    // The Company aggregate validates the base currency (ISO-4217) and makes it
    // immutable; an unknown code throws IllegalArgumentException -> 400.
    Company company =
        new Company(newCompanyId, name, baseCurrency, defaultLanguage, legalEmployerId);
    company.setCompanyId(tenant);
    Company savedCompany = companyRepository.save(company);

    // The first business is a top-level org unit (no parent) of type business_unit.
    OrgUnit firstBusiness = new OrgUnit(businessName, OrgUnitType.from(businessType), null);
    firstBusiness.setCompanyId(tenant);
    OrgUnit savedBusiness = orgUnitRepository.save(firstBusiness);

    // Flush so the inserts (and any RLS WITH CHECK violation) surface inside this
    // transaction, before the outbox row, rather than at commit.
    companyRepository.flush();
    orgUnitRepository.flush();

    // Build the CompanyCreated GenericRecord and serialize it for the outbox payload.
    GenericRecord event = CompanyCreatedSchema.toRecord(savedCompany);
    byte[] payload = AvroSerde.serialize(event);

    // The outbox INSERT runs on this transaction's connection (rule 3): it commits
    // atomically with the company + org_unit above. company_id is a UUID column on
    // the outbox; the new tenant id is a UUID by construction (generated server-side).
    outboxWriter.write(
        CompanyCreatedSchema.AGGREGATE_TYPE,
        savedCompany.getId().toString(),
        CompanyCreatedSchema.EVENT_TYPE,
        payload,
        null,
        newCompanyId,
        savedCompany.getCreatedAt());

    return new CreateCompanyResult(savedCompany, savedBusiness);
  }

  /**
   * Adds a business (org unit) under the bound company, in its own transaction. The tenant scope is
   * already bound at the request edge for this tenant-scoped endpoint, so {@link
   * RlsAutoApplyAspect} sets the GUC to the existing tenant and the RLS {@code WITH CHECK} confines
   * the row to it.
   *
   * @param command the create-business command (company id from the path, validated name/type)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrgUnit addBusiness(CreateBusinessCommand command) {
    String tenant = TenantContext.require().companyId();

    // In M1.2's minimal flat model a "business" added to a company is a top-level org
    // unit under that company (parent_id null) — the company itself is the tenant root,
    // not an org_unit, so a business is a top-level node, not a child of another node.
    // The full nested org tree (branch/outlet/team hierarchy) arrives in a later task.
    // RLS confines the row to the bound tenant; company_id is stamped from the scope,
    // never from the request body (rule 5).
    OrgUnit orgUnit = new OrgUnit(command.name(), OrgUnitType.from(command.type()), null);
    orgUnit.setCompanyId(tenant);
    return orgUnitRepository.save(orgUnit);
  }

  /**
   * All org units visible to the bound tenant — no {@code WHERE company_id}; the result set is
   * constrained solely by the auto-applied RLS policy. This is the read path the cross-tenant
   * isolation test relies on to prove a company/org_unit created under tenant A is invisible to
   * tenant B.
   */
  @Transactional(readOnly = true)
  public List<OrgUnit> findOrgUnitsForCurrentTenant() {
    return orgUnitRepository.findAll();
  }

  /**
   * All companies visible to the bound tenant — RLS-constrained. A company is its own tenant, so
   * within tenant A's scope this returns only company A.
   */
  @Transactional(readOnly = true)
  public List<Company> findCompaniesForCurrentTenant() {
    return companyRepository.findAll();
  }
}
