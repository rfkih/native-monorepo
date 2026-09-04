package id.co.nativeapp.finance.inventory.domain;

/**
 * The requested {@code cutoverPeriod} is sealed — a {@code tax_filing} row already exists for it
 * (the same {@code tax_filing} sealed-period probe every other posting writer honours, ADR 0028) —
 * so perpetual inventory cannot be activated with that cutover. Surfaced as {@code 422}.
 */
public class InventoryMethodSealedPeriodException extends RuntimeException {

  public InventoryMethodSealedPeriodException(String message) {
    super(message);
  }
}
