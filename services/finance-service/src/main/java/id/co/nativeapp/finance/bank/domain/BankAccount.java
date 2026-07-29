package id.co.nativeapp.finance.bank.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code bank_account} aggregate — a company's real bank/cash account (Phase 3 bank
 * reconciliation, ADR 0016), the mirror of {@code Vendor} (Phase 2 AP). A bank account is
 * <strong>finance-local</strong>: created inside finance-service and referenced by {@link
 * BankStatementLine#getBankAccountId()}.
 *
 * <p>All bank accounts share ONE {@code BANK} GL control account (1000, V30); per-account balances
 * live in this sub-ledger (the reconciled {@link BankStatementLine}s), not the GL — exactly as AR
 * uses one 1200 and AP one 2000.
 *
 * <p>{@code currency} is set at creation and immutable (mirrors the company base-currency invariant
 * — CLAUDE.md): a statement line imported for this account always inherits it, so the account never
 * silently drifts into a mixed-currency sub-ledger. {@code accountNumber} is stored MASKED to its
 * last 4 digits ({@code "****1234"}): the FULL bank account number is rule-6 PII (CLAUDE.md: "bank
 * account … column-level encrypted, never logged") and finance-service has no column-encryption
 * infrastructure, so the full number is NEVER persisted — only the masked form (which is not the
 * protected PII, the same convention as a card's last-4 on a POS receipt).
 *
 * <p>Extends {@link Auditable}, so it carries the six audit + tenancy columns and is covered by the
 * {@code bank_account} RLS policy (V29), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "bank_account")
public class BankAccount extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "account_number", length = 20)
  private String accountNumber;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected BankAccount() {
    // for JPA
  }

  /**
   * Creates an active bank account. {@code accountNumber} is optional and stored MASKED (last-4
   * only; the full number is rule-6 PII and is never persisted); {@code name} and {@code currency}
   * are required.
   *
   * @throws IllegalArgumentException if {@code currencyCode} is not a valid ISO-4217 code
   */
  public static BankAccount create(String name, String accountNumber, String currencyCode) {
    Objects.requireNonNull(currencyCode, "currencyCode");
    BankAccount account = new BankAccount();
    account.id = UUID.randomUUID();
    account.name = requireName(name);
    account.accountNumber = maskAccountNumber(accountNumber);
    account.currency = Currency.getInstance(currencyCode).getCurrencyCode();
    account.active = true;
    return account;
  }

  /**
   * Updates the mutable details (name required; account number optional + masked; currency
   * immutable).
   */
  public void updateDetails(String name, String accountNumber) {
    this.name = requireName(name);
    this.accountNumber = maskAccountNumber(accountNumber);
  }

  /** Sets the active flag (a deactivated account is hidden from the default list but retained). */
  public void setActive(boolean active) {
    this.active = active;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("bank account name must not be blank");
    }
    return trimmed;
  }

  /**
   * Masks a bank account number to its last 4 digits ({@code "****1234"}). The FULL number is
   * rule-6 PII ("bank account … column-level encrypted, never logged"); finance-service has no
   * column encryption, so the full number is NEVER persisted — only this masked form (not the
   * protected PII, the same convention as a card's last-4 on a POS receipt). Blank → {@code null}.
   */
  private static String maskAccountNumber(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= 4 ? trimmed : "****" + trimmed.substring(trimmed.length() - 4);
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getAccountNumber() {
    return accountNumber;
  }

  public String getCurrency() {
    return currency.strip();
  }

  public boolean isActive() {
    return active;
  }
}
