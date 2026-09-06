package id.co.nativeapp.restaurant.inventory.domain;

/**
 * Raised when an ingredient's base unit cannot be re-expressed as asked.
 *
 * <p>Distinct from {@link IngredientUnitChangeException}, which refuses a unit change that would
 * REINTERPRET the numbers already on the row ("10 pcs" silently becoming "10 g"). A conversion is
 * the opposite operation: it changes the unit AND rescales every figure so the physical truth is
 * unchanged. It fails only when the arithmetic cannot be carried out honestly — a factor that is
 * not a positive whole number, or a result that would not fit the columns holding it.
 */
public class IngredientUnitConversionException extends RuntimeException {

  public IngredientUnitConversionException(String message) {
    super(message);
  }
}
