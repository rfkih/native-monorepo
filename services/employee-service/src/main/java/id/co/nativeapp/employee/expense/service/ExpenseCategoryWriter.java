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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the {@link ExpenseCategory} admin catalog. A
 * distinct bean (the {@code *Writer} pattern) so each method is invoked through the Spring proxy
 * and the {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5).
 *
 * <p><strong>Duplicate-name race backstop (data-engineer review).</strong> {@code create}/{@code
 * update} pre-check {@link ExpenseCategoryRepository#existsByNameIgnoreCase} for a friendly {@code
 * 409} on the common case, but that check-then-act is itself racy: two concurrent requests for the
 * SAME name can both pass the pre-check before either commits. The {@code uq_expense_category_name}
 * unique index is the real backstop — both {@code create} and {@code update} therefore {@code
 * saveAndFlush} inside a {@code try/catch} that translates a resulting {@link
 * DataIntegrityViolationException} into the same {@link DuplicateCategoryNameException} (→ 409) the
 * pre-check throws, so a raced request never leaks a raw {@code 500}.
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
   *     exists in the tenant (→ 409) — either the pre-check, or the {@code
   *     uq_expense_category_name} backstop under a concurrent race
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
    try {
      return repository.saveAndFlush(category);
    } catch (DataIntegrityViolationException raced) {
      // A concurrent request for the SAME name won the race between our pre-check and this
      // insert; the unique index is the real backstop (this pre-check is only the friendly path).
      throw new DuplicateCategoryNameException(name);
    }
  }

  /**
   * Applies a partial update ({@code null} leaves a field unchanged).
   *
   * @throws CategoryNotFoundException if the category is unknown in this tenant (→ 404)
   * @throws DuplicateCategoryNameException if renaming to a name another category already holds (→
   *     409) — either the pre-check, or the {@code uq_expense_category_name} backstop under a
   *     concurrent race
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
    try {
      return repository.saveAndFlush(category);
    } catch (DataIntegrityViolationException raced) {
      throw new DuplicateCategoryNameException(newName != null ? newName : category.getName());
    }
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
