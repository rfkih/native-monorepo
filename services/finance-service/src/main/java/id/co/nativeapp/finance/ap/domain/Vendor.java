package id.co.nativeapp.finance.ap.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code vendor} aggregate — the bill-from party dimension in Native (Phase 2 AP), the mirror
 * of {@code Customer} (Phase 1 AR). A vendor is <strong>finance-local</strong>: it is created
 * inside finance-service and referenced by {@link Bill#getVendorId()}. It is NOT a cross-service
 * reference (rule 1) — no other service owns vendors today.
 *
 * <p>Extends {@link Auditable}, so it carries the six audit + tenancy columns and is covered by the
 * {@code vendor} RLS policy (V27), scoped to {@code app.current_tenant} (rules 4 + 5).
 *
 * <p>{@code email} / {@code tax_id} are ordinary business-contact fields (a vendor is an external
 * counterparty, not an employee): they are NOT the column-encrypted PII class (salary / NIK /
 * bank), so they are stored in the clear like {@code name}. They must never be logged.
 */
@Entity
@Table(name = "vendor")
public class Vendor extends Auditable {

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

  /** The default payment term in days the bill form preselects for this vendor (ADR 0084). */
  @Column(name = "payment_term_days")
  private Integer paymentTermDays;

  protected Vendor() {
    // for JPA
  }

  /**
   * Creates an active vendor. {@code email} / {@code taxId} are optional (blank is normalised to
   * {@code null}); {@code name} is required.
   */
  public static Vendor create(String name, String email, String taxId) {
    return create(name, email, taxId, null);
  }

  /** As {@link #create(String, String, String)} with a default payment term (ADR 0084). */
  public static Vendor create(String name, String email, String taxId, Integer paymentTermDays) {
    Vendor vendor = new Vendor();
    vendor.id = UUID.randomUUID();
    vendor.name = requireName(name);
    vendor.email = blankToNull(email);
    vendor.taxId = blankToNull(taxId);
    vendor.active = true;
    vendor.paymentTermDays = requireTerm(paymentTermDays);
    return vendor;
  }

  /** Updates the mutable contact fields (name required; email/tax id optional). */
  public void updateDetails(String name, String email, String taxId) {
    this.name = requireName(name);
    this.email = blankToNull(email);
    this.taxId = blankToNull(taxId);
  }

  /** Sets (or clears, with null) the default payment term (ADR 0084). */
  public void setPaymentTermDays(Integer paymentTermDays) {
    this.paymentTermDays = requireTerm(paymentTermDays);
  }

  private static Integer requireTerm(Integer paymentTermDays) {
    if (paymentTermDays != null && paymentTermDays < 0) {
      throw new IllegalArgumentException("vendor payment term must not be negative");
    }
    return paymentTermDays;
  }

  /** Sets the active flag (a deactivated vendor is hidden from the default list but retained). */
  public void setActive(boolean active) {
    this.active = active;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("vendor name must not be blank");
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

  public Integer getPaymentTermDays() {
    return paymentTermDays;
  }
}
