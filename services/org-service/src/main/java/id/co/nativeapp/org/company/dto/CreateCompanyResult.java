package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.domain.OrgUnit;

/**
 * Outcome of {@link CompanyService#createCompany(CreateCompanyCommand)} — the new company plus its
 * first business, created in one transaction along with the {@code CompanyCreated} outbox row.
 *
 * @param company the newly created company (its id is the new tenant id)
 * @param firstBusiness the first business (top-level org unit) created with it
 */
public record CreateCompanyResult(Company company, OrgUnit firstBusiness) {}
