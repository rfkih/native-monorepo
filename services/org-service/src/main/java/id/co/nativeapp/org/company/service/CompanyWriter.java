package id.co.nativeapp.org.company.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.domain.LegalEmployer;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.domain.Vertical;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
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
import java.util.Locale;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.dao.DataIntegrityViolationException;
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
   * @param command the create-company command (name, currency, language, country + optional funnel
   *     fields, first business) — mirrors {@link #addBusiness(CreateBusinessCommand)}'s shape
   * @param companyCode the minted 6-char login-namespace code (ADR 0054); on the vanishingly
   *     unlikely {@code uq_company_company_code} collision this throws {@link
   *     CompanyCodeCollisionException} and {@link CompanyService} retries with a fresh code
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CreateCompanyResult create(
      UUID newCompanyId, CreateCompanyCommand command, String companyCode) {
    String name = command.name();

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

    // The Company aggregate validates the base currency (ISO-4217) and country (ISO 3166-1)
    // and makes both immutable; an unknown code throws IllegalArgumentException -> 400.
    Company company =
        new Company(
            newCompanyId,
            name,
            command.baseCurrency(),
            command.defaultLanguage(),
            legalEmployerId,
            command.country(),
            command.phone(),
            command.companySize(),
            command.primaryInterest(),
            companyCode,
            // ADR 0070: the vertical is a COMPANY attribute now (it used to live on the
            // business-unit node). Required and immutable, like the base currency.
            Vertical.fromKey(command.vertical()));
    // New companies start on the free tier (ADR 0044 D4 "signup-default-FREE", delivered with
    // the ADR 0047 three-tier pricing); pre-existing rows keep the V10 grandfather FULL default.
    company.changePlanTier("FREE");
    company.setCompanyId(tenant);
    Company savedCompany = companyRepository.save(company);

    // ADR 0070: the tree is flat, so the bootstrap seeds exactly ONE node — the company's first
    // OUTLET, named after the company. There is no division level to create it under, and no
    // second name to ask the owner for at signup; they rename the outlet on the Outlets page (the
    // backend has no i18n, so the company name is the only sensible default).
    OrgUnit firstOutlet = new OrgUnit(name, OrgUnitType.OUTLET, legalEmployerId, today());
    firstOutlet.setCompanyId(tenant);
    OrgUnit savedOutlet = orgUnitRepository.save(firstOutlet);

    // Flush so the inserts (and any RLS WITH CHECK violation) surface inside this transaction,
    // before the outbox rows, rather than at commit. flush() synchronizes the WHOLE persistence
    // context, so a duplicate company_code (ADR 0054) may surface on the first flush regardless of
    // which repository triggers it — translate that one UNIQUE-index violation into a retryable
    // CompanyCodeCollisionException; every other integrity violation (e.g. an RLS WITH CHECK
    // failure) propagates unchanged.
    try {
      legalEmployerRepository.flush();
      companyRepository.flush();
      orgUnitRepository.flush();
    } catch (DataIntegrityViolationException e) {
      if (mentionsCompanyCodeIndex(e)) {
        throw new CompanyCodeCollisionException();
      }
      throw e;
    }

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

    // OrgUnitCreated for the seeded outlet — downstream services cache the org tree from these
    // events; this is the tenant's first (and, until the owner adds more, only) node.
    writeOrgUnitCreated(savedOutlet, newCompanyId);

    return new CreateCompanyResult(savedCompany, savedOutlet);
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
   * Whether the failure chain names the {@code uq_company_company_code} UNIQUE index (ADR 0054) — a
   * duplicate {@code company_code}. Prefers Hibernate's parsed {@link
   * org.hibernate.exception.ConstraintViolationException#getConstraintName()} when populated, and
   * falls back to a case-insensitive scan of the whole cause chain's messages (robust across the
   * Spring {@link DataIntegrityViolationException} → Hibernate → PostgreSQL layers, where the
   * driver puts the violated index name in the error text). Both together make it resilient to a
   * dialect that leaves the constraint name null OR a driver that omits it from the message.
   */
  private static boolean mentionsCompanyCodeIndex(Throwable e) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c instanceof org.hibernate.exception.ConstraintViolationException cve) {
        String name = cve.getConstraintName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains("uq_company_company_code")) {
          return true;
        }
      }
      String message = c.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("uq_company_company_code")) {
        return true;
      }
    }
    return false;
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
