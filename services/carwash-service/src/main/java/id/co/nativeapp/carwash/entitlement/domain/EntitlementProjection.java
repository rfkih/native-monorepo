package id.co.nativeapp.carwash.entitlement.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The local entitlement read model row: {@code (company_id, module_key) -> entitled}.
 * carwash-service maintains this projection by CONSUMING {@code EntitlementGranted}/{@code
 * EntitlementRevoked} from entitlement-service (rule 2 — no synchronous call; a locally cached read
 * model). It backs the DB-backed {@link
 * id.co.nativeapp.carwash.entitlement.service.ProjectionEntitlementLoader} the shared {@code
 * libs/entitlement-check} cache consults on a miss, so the record-wash gate can answer "is this
 * company entitled to carwash?" without a sync call.
 *
 * <p>It is an <em>app-maintained</em> read model (NOT a Debezium-CDC-derived projection), so — like
 * employee's {@code org_unit_projection} — the rule-4 Auditable + rule-5 RLS guarantees apply
 * uniformly: it extends {@link Auditable} and is under the {@code entitlement_projection} RLS
 * policy. The consumer writes it inside a {@link id.co.nativeapp.tenant.TenantContext} scope bound
 * to the EVENT's {@code company_id}, so the RLS {@code WITH CHECK} passes.
 */
@Entity
@Table(name = "entitlement_projection")
public class EntitlementProjection extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "module_key", nullable = false, updatable = false)
  private String moduleKey;

  @Column(name = "entitled", nullable = false)
  private boolean entitled;

  protected EntitlementProjection() {
    // for JPA
  }

  /**
   * Creates a projection row for a (company, module) pair.
   *
   * @param moduleKey the module this row is for
   * @param entitled whether the company is currently entitled to the module
   */
  public EntitlementProjection(String moduleKey, boolean entitled) {
    this.id = UUID.randomUUID();
    this.moduleKey = Objects.requireNonNull(moduleKey, "moduleKey");
    this.entitled = entitled;
  }

  /** Applies the new entitled state from a consumed Granted/Revoked event. */
  public void setEntitled(boolean entitled) {
    this.entitled = entitled;
  }

  public UUID getId() {
    return id;
  }

  public String getModuleKey() {
    return moduleKey;
  }

  public boolean isEntitled() {
    return entitled;
  }
}
