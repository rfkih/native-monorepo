package id.co.nativeapp.finance.companyexpense.projection;

import java.util.UUID;

/**
 * Read projection for an INVENTORY expense's ingredient lines (ordered by {@code line_no}).
 * snake_case aliases map to these camelCase getters; service + repository layers only.
 */
public interface CompanyExpenseLineView {

  UUID getId();

  int getLineNo();

  UUID getIngredientId();

  String getIngredientName();

  long getQtyBase();

  long getValueMinor();

  /** The receipt wording, or {@code null} when the line is named after the inventory item. */
  String getDescription();
}
