package id.co.nativeapp.finance.ar.domain;

import java.util.UUID;

/**
 * Thrown when an invoice id is not found in the bound tenant (RLS-scoped; a foreign-tenant invoice
 * is indistinguishable from a non-existent one — anti-enumeration) → HTTP 404.
 */
public class InvoiceNotFoundException extends RuntimeException {

  public InvoiceNotFoundException(UUID invoiceId) {
    super("invoice not found: " + invoiceId);
  }
}
