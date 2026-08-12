package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.projection.CompanyCurrentView;
import id.co.nativeapp.org.company.repository.CompanyRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.NoSuchElementException;
import java.util.Optional;
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
        view.getFirstBusinessId(),
        view.getPlanTier(),
        view.getCompanyCode());
  }

  /**
   * The bound tenant's 6-char {@code company_code} (ADR 0054) — the login-namespace prefix the
   * invite flow composes into {@code <company_code>.<local>} usernames. Deliberately JOIN-FREE
   * (unlike {@link #findCurrentCompany()}, which joins {@code org_unit}): the invite path must
   * resolve the code even when the org tree is absent, and RLS already scopes the single {@code
   * company} row to the bound tenant.
   *
   * @return the company code for the bound tenant
   * @throws NoSuchElementException if no company exists for the bound tenant
   */
  @Transactional(readOnly = true)
  public String findCurrentCompanyCode() {
    return companyRepository
        .findCurrentCompanyCode()
        .orElseThrow(() -> new NoSuchElementException("No company found for the current tenant"));
  }

  /**
   * Like {@link #findCurrentCompanyCode()} but tolerant of a not-yet-bootstrapped tenant: returns
   * an empty {@link Optional} instead of throwing when no {@code company} row exists for the bound
   * tenant. Used by the DISPLAY path ({@link
   * id.co.nativeapp.org.user.service.UserService#listUsers()}), where a missing code simply means
   * "there is no {@code <company_code>.} prefix to strip" — never a 404. The strict {@link
   * #findCurrentCompanyCode()} stays for the invite path, which genuinely needs the code to compose
   * the stored {@code <company_code>.<local>} login.
   *
   * @return the bound tenant's {@code company_code}, or empty if the tenant has no company row yet
   */
  @Transactional(readOnly = true)
  public Optional<String> findCurrentCompanyCodeIfPresent() {
    // Deliberately calls the repository directly rather than delegating to/from the strict
    // findCurrentCompanyCode() — a same-bean self-invocation would bypass the Spring proxy and the
    // RlsAutoApplyAspect that binds the tenant GUC (see the class javadoc). Do not "DRY" these two.
    return companyRepository.findCurrentCompanyCode();
  }
}
