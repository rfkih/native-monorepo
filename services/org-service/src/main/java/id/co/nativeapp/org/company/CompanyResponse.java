package id.co.nativeapp.org.company;

import java.util.UUID;

/**
 * Create-company response body. Exposes the new company plus the first business id, so the caller
 * (the onboarding wizard) can navigate to the freshly created tenant.
 */
public record CompanyResponse(
    UUID id,
    String name,
    String baseCurrency,
    String defaultLanguage,
    UUID legalEmployerId,
    UUID firstBusinessId) {

  static CompanyResponse from(Company company, OrgUnit firstBusiness) {
    return new CompanyResponse(
        company.getId(),
        company.getName(),
        company.getBaseCurrency(),
        company.getDefaultLanguage(),
        company.getLegalEmployerId(),
        firstBusiness.getId());
  }
}
