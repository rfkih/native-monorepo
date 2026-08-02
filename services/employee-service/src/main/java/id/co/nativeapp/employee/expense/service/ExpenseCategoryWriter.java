package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.expense.domain.CategoryNotFoundException;
import id.co.nativeapp.employee.expense.domain.DuplicateCategoryNameException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the {@link ExpenseCategory} admin catalog. A
 * distinct bean (the {@code *Writer} pattern) so each method is invoked through the Spring proxy
 * and the {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5).
 */
@Component
public class ExpenseCategoryWriter {

  /**
   * The default categories every tenant is seeded with (ADR 0030): {@code name -> gl_hint}, in
   * display order. {@code seedDefaults} inserts only the ones a tenant lacks, so it is safe to call
   * repeatedly (idempotent).
   */
  private static final List<String[]> DEFAULTS =
      List.of(
          new String[] {"General", ""},
          new String[] {"COGS", "cogs"},
          new String[] {"Supplies", "supplies"},
          new String[] {"Utilities", "utilities"});

  private final ExpenseCategoryRepository repository;

  public ExpenseCategoryWriter(ExpenseCategoryRepository repository) {
    this.repository = repository;
  }

  /**
   * Creates a new active category.
   *
   * @throws DuplicateCategoryNameException if a category with this name (case-insensitive) already
   *     exists in the tenant (→ 409)
   * @throws id.co.nativeapp.employee.expense.domain.InvalidGlHintException if {@code glHint} is not
   *     whitelisted (→ 422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseCategory create(String name, String glHint, boolean taxable) {
    String tenant = TenantContext.require().companyId();
    if (repository.existsByNameIgnoreCase(name)) {
      throw new DuplicateCategoryNameException(name);
    }
    ExpenseCategory category = new ExpenseCategory(name, glHint, taxable);
    category.setCompanyId(tenant);
    return repository.save(category);
  }

  /**
   * Applies a partial update ({@code null} leaves a field unchanged).
   *
   * @throws CategoryNotFoundException if the category is unknown in this tenant (→ 404)
   * @throws DuplicateCategoryNameException if renaming to a name another category already holds (→
   *     409)
   * @throws id.co.nativeapp.employee.expense.domain.InvalidGlHintException if a non-null {@code
   *     newGlHint} is not whitelisted (→ 422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseCategory update(
      UUID id, String newName, String newGlHint, Boolean newTaxable, Boolean newActive) {
    ExpenseCategory category =
        repository.findById(id).orElseThrow(() -> new CategoryNotFoundException(id));
    if (newName != null
        && !newName.equalsIgnoreCase(category.getName())
        && repository.existsByNameIgnoreCase(newName)) {
      throw new DuplicateCategoryNameException(newName);
    }
    category.update(newName, newGlHint, newTaxable, newActive);
    return repository.save(category);
  }

  /**
   * Idempotently seeds the tenant's default categories (ADR 0030) — inserts ONLY the ones the
   * tenant currently lacks (matched case-insensitively by name), so a repeat call is a no-op.
   *
   * @return the freshly-inserted categories (empty if the tenant already had every default)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<ExpenseCategory> seedDefaults() {
    String tenant = TenantContext.require().companyId();
    List<ExpenseCategory> created = new ArrayList<>();
    for (String[] entry : DEFAULTS) {
      String name = entry[0];
      String glHint = entry[1];
      if (!repository.existsByNameIgnoreCase(name)) {
        ExpenseCategory category = new ExpenseCategory(name, glHint, false);
        category.setCompanyId(tenant);
        created.add(repository.save(category));
      }
    }
    return created;
  }
}
