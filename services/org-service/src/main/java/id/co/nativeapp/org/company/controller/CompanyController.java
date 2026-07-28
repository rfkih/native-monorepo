package id.co.nativeapp.org.company.controller;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateBusinessRequest;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyRequest;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.dto.OrgUnitResponse;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.config.DevTenantFilter;
import id.co.nativeapp.org.config.TenantAccessDeniedException;
import id.co.nativeapp.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/companies} — create a company (bootstrapping a new tenant) and add businesses.
 *
 * <p><strong>Create-company is the tenant bootstrap.</strong> {@code POST /api/v1/companies}
 * creates a NEW tenant, so it does not require an inbound tenant scope (it is exempt from {@link
 * DevTenantFilter}'s tenant requirement, like {@code /healthz}). The actor for the bootstrap comes
 * from the {@code X-Actor} header (the documented stand-in for the JWT {@code sub} / authenticated
 * owner); the controller passes it into the command, and {@link CompanyService} opens the new
 * tenant scope with it. The new {@code companyId} is generated server-side and never taken from the
 * body (rule 5).
 *
 * <p><strong>Create-business is tenant-scoped.</strong> {@code POST
 * /api/v1/companies/{companyId}/businesses} runs under the already-bound tenant scope (the {@code
 * DevTenantFilter} binds it from the headers); the company id comes from the path.
 *
 * <p>Returns {@code 201 Created} with a {@code Location} header pointing at the new resource.
 */
@Tag(
    name = "Companies",
    description = "Create a company (tenant bootstrap) and add businesses under it")
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

  private final CompanyService companyService;

  public CompanyController(CompanyService companyService) {
    this.companyService = companyService;
  }

  /**
   * Create a company (new tenant) with its base currency, default language, and first business, in
   * one transaction; emits {@code CompanyCreated}. Returns {@code 201} + {@code Location}.
   */
  @Operation(
      summary = "Create a company (tenant bootstrap)",
      description =
          "Creates a new company (and its tenant) with its immutable base currency, default"
              + " language, and first business in one transaction; emits CompanyCreated. Returns"
              + " 201 with a Location header.")
  @PostMapping
  public ResponseEntity<CompanyResponse> createCompany(
      @RequestHeader(value = DevTenantFilter.ACTOR_HEADER, required = false) String actor,
      @Valid @RequestBody CreateCompanyRequest request) {

    CreateCompanyCommand command =
        new CreateCompanyCommand(
            request.name(),
            request.baseCurrency(),
            request.defaultLanguage(),
            request.firstBusiness().name(),
            request.firstBusiness().vertical(),
            actorOrSystem(actor));

    CreateCompanyResult result = companyService.createCompany(command);
    CompanyResponse body = CompanyResponse.from(result.company(), result.firstBusiness());
    return ResponseEntity.created(URI.create("/api/v1/companies/" + body.id())).body(body);
  }

  /**
   * Returns the company bound to the current tenant scope, together with its first business id —
   * the minimal data the console SPA needs after OIDC login to bootstrap its navigation.
   *
   * <p>The company id comes exclusively from the bound {@link TenantContext} (never from the client
   * — rule 5); RLS scopes the underlying read to the same company. Returns {@code 200} with a
   * {@link CompanyResponse} body, or {@code 404} (RFC 7807 {@code application/problem+json}) if no
   * company exists for the bound tenant.
   */
  @Operation(
      summary = "Get the current tenant's company",
      description =
          "Returns the company bound to the current tenant scope plus its first business id — the"
              + " minimal data the console needs after OIDC login. 200 with the company, or 404 if"
              + " none exists for the bound tenant.")
  @GetMapping("/current")
  public ResponseEntity<CompanyResponse> getCurrentCompany() {
    CompanyResponse body = companyService.getCurrentCompany();
    return ResponseEntity.ok(body);
  }

  /**
   * Add a business (org unit) under the company in the path, within that company's tenant scope.
   * Returns {@code 201} + {@code Location}.
   *
   * <p><strong>Confused-deputy guard (rule 5).</strong> The downstream {@link CompanyWriter} stamps
   * {@code company_id} from the bound {@link TenantContext} (never from the path), so a path {@code
   * companyId} that differs from the authenticated tenant would be silently ignored — letting a
   * caller address another company's URI and still write into their own tenant. We reject that
   * mismatch up front with a {@code 403} rather than accepting a path id that disagrees with the
   * bound scope; only a path id equal to the bound tenant proceeds.
   */
  @Operation(
      summary = "Add a business to a company",
      description =
          "Adds a business (org unit) under the company in the path, within that company's tenant"
              + " scope. The path companyId must equal the bound tenant (else 403). Returns 201"
              + " with a Location header.")
  @PostMapping("/{companyId}/businesses")
  public ResponseEntity<OrgUnitResponse> addBusiness(
      @PathVariable UUID companyId, @Valid @RequestBody CreateBusinessRequest request) {

    String boundTenant = TenantContext.require().companyId();
    if (!companyId.toString().equals(boundTenant)) {
      throw new TenantAccessDeniedException();
    }

    CreateBusinessCommand command =
        new CreateBusinessCommand(companyId, request.name(), request.vertical());

    OrgUnit created = companyService.addBusiness(command);
    OrgUnitResponse body = OrgUnitResponse.from(created);
    return ResponseEntity.created(
            URI.create("/api/v1/companies/" + companyId + "/businesses/" + body.id()))
        .body(body);
  }

  /**
   * The bootstrap actor: the {@code X-Actor} header when present (dev stand-in for the JWT {@code
   * sub}), or a system principal otherwise. In production the gateway always supplies an
   * authenticated principal; this fallback keeps the bootstrap usable in a header-less dev/test
   * call without binding a blank actor (which {@code TenantContext} rejects).
   */
  private static String actorOrSystem(String actor) {
    return (actor == null || actor.isBlank()) ? "system@org-service" : actor;
  }
}
