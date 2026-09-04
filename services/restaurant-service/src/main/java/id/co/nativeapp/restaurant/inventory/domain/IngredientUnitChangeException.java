package id.co.nativeapp.restaurant.inventory.domain;

/**
 * Thrown when an ingredient's BASE unit would change while it still holds stock or value.
 *
 * <p>There is no meaningful conversion between count and weight ("10 pcs" is not "10 g", and no
 * ratio exists to derive one), so silently rewriting the unit would reinterpret the on-hand
 * quantity and poison the moving-average cost with it — an owner correcting a mis-created item
 * would land a 1000x error in their books. The change is refused instead: zero the stock (a stock
 * opname), change the unit, then re-enter the quantity in the new unit.
 *
 * <p>Changing only the DISPLAY unit (the kg/g · liter/ml label, V37) is always allowed — the base
 * quantity is untouched by it.
 */
public class IngredientUnitChangeException extends RuntimeException {

  public IngredientUnitChangeException(String message) {
    super(message);
  }
}
