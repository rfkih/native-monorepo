package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.operator.dto.OutletOperatorPolicyResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads/sets the per-outlet operator-PIN policy for the bound company (ADR 0049): {@code GET
 * /api/v1/operators/policy} (POS_ROLES) and {@code PUT
 * /api/v1/employees/outlet-pin-policy/{businessId}} (owner/manager, DASHBOARD_ROLES) both ride this
 * one orchestrator — the endpoints differ only in their gateway gate, not in the underlying
 * read/write unit of work, which lives in {@link OutletOperatorPolicyReader} / {@link
 * OutletOperatorPolicyWriter} (the {@code *Writer}/{@code *Reader} pattern, so the Spring proxy +
 * the auto-RLS aspect engage).
 */
@Service
public class OutletOperatorPolicyService {

  private final OutletOperatorPolicyReader reader;
  private final OutletOperatorPolicyWriter writer;

  public OutletOperatorPolicyService(
      OutletOperatorPolicyReader reader, OutletOperatorPolicyWriter writer) {
    this.reader = reader;
    this.writer = writer;
  }

  /**
   * The outlet's current operator-PIN policy.
   *
   * @param rawBusinessId the outlet id as received from the query string; must be a non-blank UUID
   * @throws IllegalArgumentException when {@code rawBusinessId} is missing, blank, or not a valid
   *     UUID (→ 400, via the shared {@code ApiExceptionHandler})
   */
  public OutletOperatorPolicyResponse getPolicy(String rawBusinessId) {
    TenantContext.require();
    UUID businessId = parseBusinessId(rawBusinessId);
    return new OutletOperatorPolicyResponse(reader.requirePin(businessId));
  }

  /** Sets (or updates) the operator-PIN policy for an outlet under the bound company. */
  public void setPolicy(UUID businessId, boolean requirePin) {
    TenantContext.require();
    writer.upsert(businessId, requirePin);
  }

  private static UUID parseBusinessId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("businessId is required");
    }
    try {
      return UUID.fromString(raw.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("businessId must be a valid UUID");
    }
  }
}
