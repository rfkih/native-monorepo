package id.co.nativeapp.finance.bank.domain;

import java.util.UUID;

/**
 * Thrown when a bank statement line id is not found in the bound tenant (RLS-scoped; a
 * foreign-tenant line is indistinguishable from a non-existent one — anti-enumeration) → HTTP 404.
 */
public class StatementLineNotFoundException extends RuntimeException {

  public StatementLineNotFoundException(UUID lineId) {
    super("bank statement line not found: " + lineId);
  }
}
