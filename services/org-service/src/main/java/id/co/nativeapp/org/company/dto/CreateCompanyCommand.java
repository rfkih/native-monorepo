package id.co.nativeapp.org.company.dto;

/**
 * The application command to create a company (and its first business), assembled at the request
 * edge from the {@code POST /api/v1/companies} body plus the request-edge actor.
 *
 * <p>The {@code actor} is included here (unlike the tenant-scoped commands in other services)
 * because create-company BOOTSTRAPS a new tenant: there is no inbound {@code TenantContext} to read
 * the actor from at the service layer, so the controller passes the request-edge actor (the {@code
 * X-Actor} header / JWT {@code sub}) through, and the service opens the new tenant scope with it
 * ({@code TenantContext.callAs(newCompanyId, actor, ...)}). The {@code company_id} is NOT here — it
 * is generated server-side as the new tenant id (rule 5: tenant is never trusted from the body).
 *
 * @param name the company name
 * @param baseCurrency the ISO-4217 base currency code (immutable once set)
 * @param defaultLanguage the company default language
 * @param businessName the first business (org-unit) name
 * @param businessType the first business (org-unit) type
 * @param actor the acting principal from the request edge (JWT {@code sub} / {@code X-Actor})
 */
public record CreateCompanyCommand(
    String name,
    String baseCurrency,
    String defaultLanguage,
    String businessName,
    String businessType,
    String actor) {}
