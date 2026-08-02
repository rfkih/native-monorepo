package id.co.nativeapp.employee.expense.domain;

import java.util.UUID;

/**
 * An expense category referenced by id is unknown to the bound tenant, or (when creating/editing a
 * claim) is known but no longer {@code active} — a claim may only be raised against a category the
 * tenant currently offers. Mapped to HTTP 404 by {@code EmployeeApiAdvice}.
 */
public class CategoryNotFoundException extends RuntimeException {

  public CategoryNotFoundException(UUID categoryId) {
    super("Expense category " + categoryId + " not found or inactive");
  }
}
