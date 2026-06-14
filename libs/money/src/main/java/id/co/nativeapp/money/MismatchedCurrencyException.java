package id.co.nativeapp.money;

import java.io.Serial;
import java.util.Currency;

/**
 * Thrown when an operation combines two {@link Money} values whose currencies differ (e.g. adding
 * USD to IDR), or otherwise requires currency agreement.
 *
 * <p>Money in different currencies is not commensurable: there is no meaningful sum without an
 * explicit FX conversion, which deliberately lives elsewhere (finance-service FX), never silently
 * inside this value type.
 */
public final class MismatchedCurrencyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient Currency left;
  private final transient Currency right;

  MismatchedCurrencyException(Currency left, Currency right) {
    super(
        "Cannot operate on mismatched currencies: "
            + left.getCurrencyCode()
            + " vs "
            + right.getCurrencyCode());
    this.left = left;
    this.right = right;
  }

  /** The currency of the receiver/left operand. */
  public Currency left() {
    return left;
  }

  /** The currency of the argument/right operand. */
  public Currency right() {
    return right;
  }
}
