package id.co.nativeapp.finance.ap.domain;

import java.util.UUID;

/**
 * Thrown when a vendor id is not found in the bound tenant. Because reads are RLS-scoped, a vendor
 * belonging to another tenant is indistinguishable from a non-existent one (anti-enumeration) —
 * both surface as this exception → HTTP 404.
 */
public class VendorNotFoundException extends RuntimeException {

  public VendorNotFoundException(UUID vendorId) {
    super("vendor not found: " + vendorId);
  }
}
