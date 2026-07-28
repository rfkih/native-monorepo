package id.co.nativeapp.finance.ar.domain;

import java.util.UUID;

/**
 * Thrown when a customer id is not found in the bound tenant. Because reads are RLS-scoped, a
 * customer belonging to another tenant is indistinguishable from a non-existent one
 * (anti-enumeration) — both surface as this exception → HTTP 404.
 */
public class CustomerNotFoundException extends RuntimeException {

  public CustomerNotFoundException(UUID customerId) {
    super("customer not found: " + customerId);
  }
}
