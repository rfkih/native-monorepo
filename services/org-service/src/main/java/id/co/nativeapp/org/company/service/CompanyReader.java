package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.projection.CompanyCurrentView;
import id.co.nativeapp.org.company.repository.CompanyRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the {@code GET /api/v1/companies/current} path.
 *
 * <p>A separate {@code @Component} (not a private method on {@link CompanyService}) so the
 * read-only transaction is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC, which
 * is the load-bearing mechanism that scopes the query to the bound company (rule 5).
 *
 * <p>{@code readOnly = true} signals PostgreSQL that no write will follow, allowing the driver to
 * skip acquiring a write lock and the replica to serve the read.
 */
@Component
public class CompanyReader {

  private final CompanyRepository companyRepository;

  public CompanyReader(CompanyRepository companyRepository) {
    this.companyRepository = companyRepository;
  }

  /**
   * Returns the {@link CompanyResponse} for the company bound to the current tenant scope.
   *
   * <p>The RLS policy constrains both the {@code company} and the joined {@code org_unit} rows to
   * the bound tenant; no manual {@code WHERE company_id} is needed. The first business is
   * identified by the same rule the create-company bootstrap uses: the earliest-created root {@code
   * BUSINESS_UNIT} (with {@code parent_id IS NULL}) for the company.
   *
   * @return the company response for the bound tenant
   * @throws NoSuchElementException if no company exists for the bound tenant — mapped to {@code
   *     404} by the {@link id.co.nativeapp.org.config.TenantAccessDeniedAdvice}
   */
  @Transactional(readOnly = true)
  public CompanyResponse findCurrentCompany() {
    CompanyCurrentView view =
        companyRepository
            .findCurrentView()
            .orElseThrow(
                () -> new NoSuchElementException("No company found for the current tenant"));
    return new CompanyResponse(
        view.getId(),
        view.getName(),
        view.getBaseCurrency().strip(),
        view.getDefaultLanguage(),
        view.getLegalEmployerId(),
        view.getFirstBusinessId());
  }
}
