package id.co.nativeapp.employee.expense.domain;

/**
 * An expense category was created/renamed to a name (case-insensitive) another category in the same
 * tenant already holds — the {@code uq_expense_category_name} backstop. Mapped to HTTP 409
 * (Conflict) by {@code EmployeeApiAdvice}.
 */
public class DuplicateCategoryNameException extends RuntimeException {

  public DuplicateCategoryNameException(String name) {
    super("An expense category named '" + name + "' already exists in this tenant");
  }
}
