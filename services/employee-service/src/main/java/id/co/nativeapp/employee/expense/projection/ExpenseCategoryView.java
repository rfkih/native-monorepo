package id.co.nativeapp.employee.expense.projection;

import java.util.UUID;

/**
 * An active expense-category row (native-query read model, {@code
 * ExpenseCategoryRepository#findAllActiveOrderedByName}).
 */
public interface ExpenseCategoryView {

  UUID getId();

  String getName();

  String getGlHint();

  Boolean getTaxable();

  Boolean getActive();
}
