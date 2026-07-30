package id.co.nativeapp.barbershop.catalog.domain;

import java.util.UUID;

/**
 * Thrown when a catalog resource (a {@code service_item}, {@code service_addon}, or {@code
 * staff_profile}) is not found in the bound tenant — either it genuinely does not exist, or it
 * belongs to another company and RLS makes it invisible (indistinguishable from "unknown", no
 * existence disclosure). Maps to {@code 404} via {@code config.CatalogAdvice}.
 */
public class CatalogItemNotFoundException extends RuntimeException {

  public CatalogItemNotFoundException(UUID id) {
    super("Catalog item not found: " + id);
  }
}
