package id.co.nativeapp.restaurant.stocktake.domain;

import java.util.UUID;

/**
 * The referenced stocktake does not exist under the bound tenant (RLS makes a cross-tenant id
 * indistinguishable from a missing one — deliberately). Mapped to {@code 404 Not Found} ({@code
 * stocktake-not-found}) by {@code StocktakeAdvice}.
 */
public class StocktakeNotFoundException extends RuntimeException {

  public StocktakeNotFoundException(UUID stocktakeId) {
    super("stocktake not found: " + stocktakeId);
  }
}
