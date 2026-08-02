package id.co.nativeapp.employee.expense.dto;

import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import java.util.UUID;

/** The response shape for one expense category (never the {@code @Entity} itself). */
public record ExpenseCategoryResponse(
    UUID id, String name, String glHint, boolean taxable, boolean active) {

  public static ExpenseCategoryResponse from(ExpenseCategory category) {
    return new ExpenseCategoryResponse(
        category.getId(),
        category.getName(),
        category.getGlHint(),
        category.isTaxable(),
        category.isActive());
  }
}
