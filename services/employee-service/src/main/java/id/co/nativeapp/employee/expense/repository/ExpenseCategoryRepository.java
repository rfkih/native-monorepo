package id.co.nativeapp.employee.expense.repository;

import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.projection.ExpenseCategoryView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link ExpenseCategory} aggregate.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5).
 */
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

  /** Active categories, name-ordered — the claim-creation picker + the admin list. */
  @Query(
      value =
          "SELECT id, name, gl_hint AS gl_hint, taxable, active"
              + " FROM expense_category WHERE active = TRUE ORDER BY name",
      nativeQuery = true)
  List<ExpenseCategoryView> findAllActiveOrderedByName();

  /** A count of case-insensitive name matches (RLS-scoped) — the duplicate-name guard. */
  @Query(
      value = "SELECT COUNT(*) FROM expense_category WHERE lower(name) = lower(:name)",
      nativeQuery = true)
  long countByNameIgnoreCase(@Param("name") String name);

  /** Whether a category with this name (case-insensitive) already exists in the bound tenant. */
  default boolean existsByNameIgnoreCase(String name) {
    return countByNameIgnoreCase(name) > 0;
  }
}
