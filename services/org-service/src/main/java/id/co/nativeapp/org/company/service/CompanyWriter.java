package id.co.nativeapp.org.company.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.domain.LegalEmployer;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.messaging.CompanyCreatedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema;
import id.co.nativeapp.org.company.projection.CompanyView;
import id.co.nativeapp.org.company.projection.OrgUnitView;
import id.co.nativeapp.org.company.repository.CompanyRepository;
import id.co.nativeapp.org.company.repository.LegalEmployerRepository;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
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
  private final LegalEmployerRepository legalEmployerRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public CompanyWriter(
      CompanyRepository companyRepository,
      OrgUnitRepository orgUnitRepository,
      LegalEmployerRepository legalEmployerRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.companyRepository = companyRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.legalEmployerRepository = legalEmployerRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
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

    // Default ONE legal_employer per company in the bootstrap, with its id equal to the
    // company id — so the historical legal_employer_id == company_id invariant still
    // holds — but now modelled as a real, first-class aggregate, not an implied identity.
    UUID legalEmployerId = newCompanyId;
    LegalEmployer legalEmployer = new LegalEmployer(legalEmployerId, name);
    legalEmployer.setCompanyId(tenant);
    legalEmployerRepository.save(legalEmployer);

    // The Company aggregate validates the base currency (ISO-4217) and makes it
    // immutable; an unknown code throws IllegalArgumentException -> 400.
    Company company =
        new Company(newCompanyId, name, baseCurrency, defaultLanguage, legalEmployerId);
    company.setCompanyId(tenant);
    Company savedCompany = companyRepository.save(company);

    // The first business is the ROOT of the org tree: a top-level BUSINESS_UNIT (no
    // parent). It is always a business_unit regardless of the requested businessType —
    // the company's root business unit is the top of the business_unit > outlet > team
    // hierarchy (sub-types are added later under it). businessType is kept in the
    // signature for API compatibility but does not override the root kind.
    OrgUnit firstBusiness =
        new OrgUnit(businessName, OrgUnitType.BUSINESS_UNIT, null, null, legalEmployerId, today());
    firstBusiness.setCompanyId(tenant);
    OrgUnit savedBusiness = orgUnitRepository.save(firstBusiness);

    // Flush so the inserts (and any RLS WITH CHECK violation) surface inside this
    // transaction, before the outbox rows, rather than at commit.
    legalEmployerRepository.flush();
    companyRepository.flush();
    orgUnitRepository.flush();

    // CompanyCreated: the outbox INSERT runs on this transaction's connection (rule 3),
    // so it commits atomically with the company + legal_employer + org_unit above.
    GenericRecord companyCreated = CompanyCreatedSchema.toRecord(savedCompany);
    outboxWriter.write(
        CompanyCreatedSchema.AGGREGATE_TYPE,
        savedCompany.getId().toString(),
        CompanyCreatedSchema.EVENT_TYPE,
        AvroSerde.serialize(companyCreated),
        null,
        newCompanyId,
        savedCompany.getCreatedAt());

    // OrgUnitCreated for the root business unit — downstream services cache the org tree
    // from these events; the bootstrap node is the first one.
    writeOrgUnitCreated(savedBusiness, newCompanyId);

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

    // A "business" added to a company is a top-level node — a root BUSINESS_UNIT (the
    // top of the business_unit > outlet > team tree). The nested sub-types are added
    // under it via the org-unit endpoint (OrgUnitWriter). RLS confines the row to the
    // bound tenant; company_id is stamped from the scope, never the body (rule 5).
    OrgUnit orgUnit =
        new OrgUnit(
            command.name(),
            OrgUnitType.BUSINESS_UNIT,
            null,
            null,
            UUID.fromString(tenant),
            today());
    orgUnit.setCompanyId(tenant);
    OrgUnit saved = orgUnitRepository.save(orgUnit);
    orgUnitRepository.flush();
    writeOrgUnitCreated(saved, UUID.fromString(tenant));
    return saved;
  }

  /**
   * Writes one {@code OrgUnitCreated} outbox row for the given org unit, on the caller's
   * transactional connection, so it commits atomically with the org_unit insert (rule 3).
   */
  void writeOrgUnitCreated(OrgUnit orgUnit, UUID companyId) {
    GenericRecord event = OrgUnitCreatedSchema.toRecord(orgUnit);
    outboxWriter.write(
        OrgUnitCreatedSchema.AGGREGATE_TYPE,
        orgUnit.getId().toString(),
        OrgUnitCreatedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        orgUnit.getCreatedAt());
  }

  /** Today's date (UTC), via the injected clock, for effective-dating new nodes. */
  private LocalDate today() {
    return LocalDate.now(clock);
  }

  /**
   * All org units visible to the bound tenant — no {@code WHERE company_id}; the result set is
   * constrained solely by the auto-applied RLS policy. This is the read path the cross-tenant
   * isolation test relies on to prove a company/org_unit created under tenant A is invisible to
   * tenant B. Uses a native projection ({@link OrgUnitView}) — pure read, never mutated/saved.
   *
   * <p>NOTE: the write-path {@code cascadeDeactivate} still calls the inherited {@code findAll()}
   * on the same repository because it mutates the returned entities and saves them — that stays on
   * the entity path (rule 3).
   */
  @Transactional(readOnly = true)
  public List<OrgUnitView> findOrgUnitsForCurrentTenant() {
    return orgUnitRepository.findAllViews();
  }

  /**
   * All companies visible to the bound tenant — RLS-constrained. A company is its own tenant, so
   * within tenant A's scope this returns only company A. Uses a native projection ({@link
   * CompanyView}) — pure read, never mutated/saved.
   */
  @Transactional(readOnly = true)
  public List<CompanyView> findCompaniesForCurrentTenant() {
    return companyRepository.findAllViews();
  }
}
