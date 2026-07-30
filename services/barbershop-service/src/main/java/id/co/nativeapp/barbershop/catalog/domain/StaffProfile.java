package id.co.nativeapp.barbershop.catalog.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code staff_profile} aggregate — the tenant's barber directory (identical shape to
 * carwash-service's {@code StaffProfile}). {@code displayLabel} is a tenant-entered, PII-free label
 * (e.g. "Budi", "Chair 2 crew") — events this service publishes carry no PII (rule 6). {@code
 * employeeId} OPTIONALLY links to the local {@code staff} read model (kept current by the {@code
 * EmployeeChanged}/{@code AssignmentChanged} consumer) so a ticket checked out against this profile
 * can attribute the {@code sales_amount@employee} commission metric to the barber who did the work,
 * without this service ever holding or displaying employee PII itself (ADR 0024).
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code staff_profile} RLS policy
 * (rule 5). {@code (company_id, business_id, display_label)} carries a UNIQUE constraint; violating
 * it on create/rename surfaces as a {@code DataIntegrityViolationException} → {@code 409} (see
 * {@code config.CatalogAdvice}).
 */
@Entity
@Table(name = "staff_profile")
public class StaffProfile extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "display_label", nullable = false)
  private String displayLabel;

  /** Optional link to the local {@code staff} read model's {@code employee_id}; no FK (rule 1). */
  @Column(name = "employee_id")
  private UUID employeeId;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected StaffProfile() {
    // for JPA
  }

  /** Creates a new staff profile with a freshly generated id. */
  public StaffProfile(UUID businessId, String displayLabel, UUID employeeId, boolean active) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.displayLabel = requireNonBlank(displayLabel, "displayLabel");
    this.employeeId = employeeId;
    this.active = active;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getDisplayLabel() {
    return displayLabel;
  }

  public UUID getEmployeeId() {
    return employeeId;
  }

  public boolean isActive() {
    return active;
  }

  public void rename(String displayLabel) {
    this.displayLabel = requireNonBlank(displayLabel, "displayLabel");
  }

  /** Relinks (or unlinks, when {@code employeeId} is {@code null}) the local staff read model. */
  public void relink(UUID employeeId) {
    this.employeeId = employeeId;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
