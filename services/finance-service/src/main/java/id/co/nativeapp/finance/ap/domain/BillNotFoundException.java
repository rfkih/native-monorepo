package id.co.nativeapp.finance.ap.domain;

import java.util.UUID;

/**
 * Thrown when a bill id is not found in the bound tenant (RLS-scoped; a foreign-tenant bill is
 * indistinguishable from a non-existent one — anti-enumeration) → HTTP 404.
 */
public class BillNotFoundException extends RuntimeException {

  public BillNotFoundException(UUID billId) {
    super("bill not found: " + billId);
  }
}
