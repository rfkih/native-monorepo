package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The local read model of consumed {@code PeriodSealed} events (design §1) — the completeness gate.
 * Keyed by (company_id, business_id, period) -> sealed_at. The run lifecycle reads this projection
 * to decide whether every expected source has sealed the period before it may transition to
 * CALCULATING/POST (ARCHITECTURE.md §4 — no running on partial data). An app-maintained projection,
 * so it extends {@link Auditable} (rule 4) and is RLS-scoped (rule 5); the consumer writes it bound
 * to the EVENT's company_id. No PII.
 */
@Entity
@Table(name = "period_seal")
public class PeriodSeal extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false)
  private UUID businessId;

  @Column(name = "period", nullable = false, length = 16)
  private String period;

  @Column(name = "sealed_at", nullable = false)
  private Instant sealedAt;

  protected PeriodSeal() {
    // for JPA
  }

  /** Creates a period-seal projection row with a freshly generated id. */
  public PeriodSeal(UUID businessId, String period, Instant sealedAt) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.period = Objects.requireNonNull(period, "period");
    this.sealedAt = Objects.requireNonNull(sealedAt, "sealedAt");
  }

  /** Refreshes the sealed timestamp from a re-projected seal (an upsert on the natural key). */
  public void applySealedAt(Instant newSealedAt) {
    this.sealedAt = Objects.requireNonNull(newSealedAt, "newSealedAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getPeriod() {
    return period;
  }

  public Instant getSealedAt() {
    return sealedAt;
  }

  @Override
  public String toString() {
    return "PeriodSeal[businessId=" + businessId + ", period=" + period + "]";
  }
}
