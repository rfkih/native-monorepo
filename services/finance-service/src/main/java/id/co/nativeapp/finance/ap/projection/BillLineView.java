package id.co.nativeapp.finance.ap.projection;

/**
 * Read projection for one {@code bill_line} row of a bill detail. Reached only from the service +
 * repository layers.
 */
public interface BillLineView {

  int getLineNo();

  String getDescription();

  int getQuantity();

  long getUnitPriceMinor();

  long getLineTotalMinor();

  boolean getIsInventory();

  java.util.UUID getIngredientId();

  String getIngredientName();

  Long getIngredientQtyBase();
}
