package id.co.nativeapp.employee.operator.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code outlet_operator_policy} aggregate (ADR 0049) — the per-outlet toggle for whether
 * {@code POST /api/v1/operators/session} must verify a PIN. Owner/manager set it via {@code PUT
 * /api/v1/employees/outlet-pin-policy/{businessId}}; {@link
 * id.co.nativeapp.employee.operator.service.OutletOperatorPolicyReader} reads it (RLS-scoped,
 * defaulting to {@code true} — PIN required — when no row exists for the outlet).
 *
 * <p>It extends {@link Auditable}, inheriting the audit + tenancy columns and the {@code
 * outlet_operator_policy} RLS policy (rule 4 + rule 5). It holds no PII.
 */
@Entity
@Table(name = "outlet_operator_policy")
public class OutletOperatorPolicy extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "require_pin", nullable = false)
  private boolean requirePin;

  protected OutletOperatorPolicy() {
    // for JPA
  }

  /**
   * Creates a fresh policy row for an outlet.
   *
   * @param businessId the outlet this policy governs; must be non-null
   * @param requirePin whether operator sign-in at this outlet requires a PIN
   */
  public OutletOperatorPolicy(UUID businessId, boolean requirePin) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.requirePin = requirePin;
  }

  /** Owner/manager toggle: replaces the current setting for this outlet. */
  public void setRequirePin(boolean requirePin) {
    this.requirePin = requirePin;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public boolean isRequirePin() {
    return requirePin;
  }

  @Override
  public String toString() {
    return "OutletOperatorPolicy[id="
        + id
        + ", businessId="
        + businessId
        + ", requirePin="
        + requirePin
        + "]";
  }
}
