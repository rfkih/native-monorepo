package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.operator.dto.OperatorRosterEntry;
import id.co.nativeapp.employee.operator.projection.OperatorRosterView;
import id.co.nativeapp.employee.operator.repository.OperatorPinRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for {@code GET /api/v1/operators/roster} (ADR 0049 P3b) — the device's PIN-picker
 * list: which employees of the bound tenant can sign in as an operator at a given outlet today.
 * {@code @Transactional(readOnly = true)} so the Spring proxy + the auto-RLS aspect engage (rule 5)
 * — RLS scopes {@code employee}, {@code operator_pin}, and {@code assignment} alike, so no manual
 * {@code company_id} filter is needed.
 *
 * <p><strong>Policy-aware (ADR 0049).</strong> The roster reflects the outlet's {@code
 * outlet_operator_policy}: a PIN-required outlet (the default) lists assigned employees who ALSO
 * carry an {@code operator_pin} row ({@link OperatorPinRepository#findRoster}, unchanged); a no-PIN
 * outlet instead lists every assigned employee with a linked login, regardless of whether they ever
 * set a PIN ({@link OperatorPinRepository#findLinkedRoster}) — a PIN row is not a login-link
 * prerequisite there.
 */
@Service
public class OperatorRosterService {

  private final OperatorPinRepository operatorPinRepository;
  private final OutletOperatorPolicyReader outletOperatorPolicyReader;
  private final Clock clock;

  public OperatorRosterService(
      OperatorPinRepository operatorPinRepository,
      OutletOperatorPolicyReader outletOperatorPolicyReader,
      Clock clock) {
    this.operatorPinRepository = operatorPinRepository;
    this.outletOperatorPolicyReader = outletOperatorPolicyReader;
    this.clock = clock;
  }

  /**
   * The roster of sign-in-capable employees for {@code businessId}, sorted by display name — the
   * PIN-holder roster for a PIN-required outlet, or the assigned+linked roster for a no-PIN outlet.
   *
   * @param rawBusinessId the outlet id as received from the query string; must be a non-blank UUID
   * @throws IllegalArgumentException when {@code rawBusinessId} is missing, blank, or not a valid
   *     UUID (→ 400, via the shared {@code ApiExceptionHandler})
   */
  @Transactional(readOnly = true)
  public List<OperatorRosterEntry> roster(String rawBusinessId) {
    TenantContext.require();
    UUID businessId = parseBusinessId(rawBusinessId);
    LocalDate asOf = LocalDate.now(clock);
    boolean requirePin = outletOperatorPolicyReader.requirePin(businessId);
    List<OperatorRosterView> rows =
        requirePin
            ? operatorPinRepository.findRoster(businessId, asOf)
            : operatorPinRepository.findLinkedRoster(businessId, asOf);
    return rows.stream()
        .map(view -> new OperatorRosterEntry(view.getEmployeeId(), view.getFullName()))
        .toList();
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
