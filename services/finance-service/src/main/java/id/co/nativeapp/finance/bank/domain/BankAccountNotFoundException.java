package id.co.nativeapp.finance.bank.domain;

import java.util.UUID;

/**
 * Thrown when a bank account id is not found in the bound tenant. Because reads are RLS-scoped, a
 * bank account belonging to another tenant is indistinguishable from a non-existent one
 * (anti-enumeration) — both surface as this exception → HTTP 404.
 */
public class BankAccountNotFoundException extends RuntimeException {

  public BankAccountNotFoundException(UUID bankAccountId) {
    super("bank account not found: " + bankAccountId);
  }
}
