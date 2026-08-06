package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.domain.OrgUnit;
import java.util.UUID;

/**
 * Create-company response body. Exposes the new company plus the first business id, so the caller
 * (the onboarding wizard) can navigate to the freshly created tenant. Also the response body for
 * {@code GET /api/v1/companies/current}, {@code GET /api/v1/companies/mine}, and {@code PUT
 * /api/v1/companies/current/plan-tier}.
 *
 * @param planTier the company's plan tier ({@code FREE} | {@code FULL} — ADR 0044); UI curation
 *     only, not an API authorization signal
 */
public record CompanyResponse(
    UUID id,
    String name,
    String baseCurrency,
    String defaultLanguage,
    UUID legalEmployerId,
    UUID firstBusinessId,
    String planTier) {

  public static CompanyResponse from(Company company, OrgUnit firstBusiness) {
    return new CompanyResponse(
        company.getId(),
        company.getName(),
        company.getBaseCurrency(),
        company.getDefaultLanguage(),
        company.getLegalEmployerId(),
        firstBusiness.getId(),
        company.getPlanTier());
  }
}
