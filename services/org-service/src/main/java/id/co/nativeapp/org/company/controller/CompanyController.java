package id.co.nativeapp.org.company.controller;

import id.co.nativeapp.org.company.domain.CountryDefaults;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.dto.CreateBusinessCommand;
import id.co.nativeapp.org.company.dto.CreateBusinessRequest;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyRequest;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.dto.OrgUnitResponse;
import id.co.nativeapp.org.company.dto.PlanTierRequest;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.config.DevTenantFilter;
import id.co.nativeapp.org.config.TenantAccessDeniedException;
import id.co.nativeapp.org.user.service.CompanyMembershipService;
import id.co.nativeapp.security.TenantBindingFilter;
import id.co.nativeapp.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
  private final CompanyMembershipService membershipService;

  public CompanyController(
      CompanyService companyService, CompanyMembershipService membershipService) {
    this.companyService = companyService;
    this.membershipService = membershipService;
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

    // Currency is DERIVED from country, never chosen — the same rule as the public signup
    // (ADR 0025): the country decides the base currency and the server re-derives it
    // authoritatively, so any baseCurrency supplied in the body is IGNORED (an API caller cannot
    // pick a currency either). country is optional on this in-app path: null → "ID" (the historical
    // Indonesian default); when present it must be a valid ISO 3166-1 alpha-2 code (→ 400).
    String country =
        CountryDefaults.requireValidCountry(request.country() != null ? request.country() : "ID");
    String baseCurrency = CountryDefaults.baseCurrencyFor(country);
    CreateCompanyCommand command =
        new CreateCompanyCommand(
            request.name(),
            baseCurrency,
            request.defaultLanguage(),
            country,
            null,
            null,
            null,
            request.firstBusiness().name(),
            request.firstBusiness().vertical(),
            actorOrSystem(actor));

    // With a verified JWT principal (oidc), the creator is BOUND to the new company (their Keycloak
    // membership gains it — membership-first with compensating removal, ADR 0021); this is both the
    // "add another business" flow and the fix for the onboarding loop where a created company was
    // unreachable by its creator. Without a JWT (dev header-trust mode), the plain bootstrap runs —
    // dev has no Keycloak to bind against.
    Jwt jwt = currentJwt();
    CreateCompanyResult result =
        jwt != null
            ? membershipService.createCompanyForUser(command, jwt.getSubject())
            : companyService.createCompany(command);
    CompanyResponse body = CompanyResponse.from(result.company(), result.firstBusiness());
    return ResponseEntity.created(URI.create("/api/v1/companies/" + body.id())).body(body);
  }

  /**
   * The caller's companies — the company-switcher list (multi-company ownership, ADR 0021). The
   * membership set comes exclusively from the VERIFIED token's {@code company_id} claim (never a
   * client parameter); in dev header-trust mode it falls back to the bound tenant (a single
   * company). A login with no company yet gets an empty list (the endpoint is tenant-optional so a
   * fresh login can call it before onboarding). Token order is preserved — the first entry is the
   * default active company.
   */
  @Operation(
      summary = "List the caller's companies",
      description =
          "Returns the companies the authenticated login belongs to (the token's company_id claim"
              + " set), token order, for the company switcher. Empty for a login with no company"
              + " yet. 200 always.")
  @GetMapping("/mine")
  public List<CompanyResponse> myCompanies(
      @RequestHeader(value = DevTenantFilter.ACTOR_HEADER, required = false) String actor) {
    Jwt jwt = currentJwt();
    if (jwt != null) {
      return companyService.findMyCompanies(
          TenantBindingFilter.extractCompanyIds(jwt), jwt.getSubject());
    }
    // Dev header-trust mode: no token — the bound tenant (if any) is the single membership.
    if (TenantContext.current().isPresent()) {
      TenantContext.Tenant tenant = TenantContext.require();
      return companyService.findMyCompanies(List.of(tenant.companyId()), tenant.actor());
    }
    return List.of();
  }

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken();
    }
    return null;
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
   * Changes the bound tenant's plan tier (ADR 0044 — "Simple mode" for UMKM), owner-only.
   *
   * <p>The gateway's route for {@code /api/v1/companies/**} lets both {@code owner} and {@code
   * manager} reach this endpoint; the service layer ({@code PlanTierWriter}) enforces the narrower
   * {@code owner}-only rule and rejects a non-owner with {@code 403}. An unwhitelisted {@code
   * planTier} value ({@code FREE} | {@code FULL} are the only valid tiers today) returns {@code
   * 422}. Returns {@code 200} with the updated {@link CompanyResponse} on success.
   */
  @Operation(
      summary = "Change the company's plan tier (owner-only)",
      description =
          "Flips the bound tenant's plan tier between FREE (Simple mode) and FULL. Owner-only —"
              + " a non-owner caller gets 403 even though the gateway route allows owner/manager."
              + " An unwhitelisted tier value returns 422. Returns 200 with the updated company.")
  @PutMapping("/current/plan-tier")
  public ResponseEntity<CompanyResponse> changePlanTier(
      @Valid @RequestBody PlanTierRequest request) {
    CompanyResponse body = companyService.changePlanTier(request.planTier());
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
