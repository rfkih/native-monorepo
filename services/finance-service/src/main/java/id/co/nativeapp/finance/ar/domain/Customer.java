package id.co.nativeapp.finance.ar.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code customer} aggregate — the first customer/party dimension in Native (Phase 1 AR). A
 * customer is <strong>finance-local</strong>: it is created inside finance-service and referenced
 * by {@link Invoice#getCustomerId()}. It is NOT a cross-service reference (rule 1) — no other
 * service owns customers today.
 *
 * <p>Extends {@link Auditable}, so it carries the six audit + tenancy columns and is covered by the
 * {@code customer} RLS policy (V24), scoped to {@code app.current_tenant} (rules 4 + 5).
 *
 * <p>{@code email} / {@code tax_id} are ordinary business-contact fields (a customer is an external
 * counterparty, not an employee): they are NOT the column-encrypted PII class (salary / NIK /
 * bank), so they are stored in the clear like {@code name}. They must never be logged.
 */
@Entity
@Table(name = "customer")
public class Customer extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "email", length = 320)
  private String email;

  @Column(name = "tax_id", length = 64)
  private String taxId;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected Customer() {
    // for JPA
  }

  /**
   * Creates an active customer. {@code email} / {@code taxId} are optional (blank is normalised to
   * {@code null}); {@code name} is required.
   */
  public static Customer create(String name, String email, String taxId) {
    Customer customer = new Customer();
    customer.id = UUID.randomUUID();
    customer.name = requireName(name);
    customer.email = blankToNull(email);
    customer.taxId = blankToNull(taxId);
    customer.active = true;
    return customer;
  }

  /** Updates the mutable contact fields (name required; email/tax id optional). */
  public void updateDetails(String name, String email, String taxId) {
    this.name = requireName(name);
    this.email = blankToNull(email);
    this.taxId = blankToNull(taxId);
  }

  /** Sets the active flag (a deactivated customer is hidden from the default list but retained). */
  public void setActive(boolean active) {
    this.active = active;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("customer name must not be blank");
    }
    return trimmed;
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getTaxId() {
    return taxId;
  }

  public boolean isActive() {
    return active;
  }
}
