package id.co.nativeapp.employee.operator.service;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.operator.domain.InvalidOperatorPinException;
import id.co.nativeapp.employee.operator.domain.OperatorNotAssignedException;
import id.co.nativeapp.employee.operator.domain.OperatorNotLinkedException;
import id.co.nativeapp.employee.operator.domain.OperatorPin;
import id.co.nativeapp.employee.operator.domain.OperatorPinLockedException;
import id.co.nativeapp.employee.operator.dto.OperatorSessionResponse;
import id.co.nativeapp.employee.operator.repository.OperatorPinRepository;
import id.co.nativeapp.security.OperatorTokenCodec;
import id.co.nativeapp.security.OperatorTokenPayload;
import id.co.nativeapp.security.OperatorTokenSigningKey;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work for {@code POST /api/v1/operators/session} (ADR 0049
 * P1): verify the PIN (RLS-scoped, lockout-guarded, outlet-assignment-checked) and, on success,
 * mint a self-contained operator token. A distinct bean (not a private method on {@link
 * OperatorSessionService}) so the Spring proxy + {@link RlsAutoApplyAspect} engage — load-bearing
 * here because the lockout counter MUST persist even on a failed attempt (rule 5, the {@code
 * *Writer} pattern).
 *
 * <p><strong>Verification order</strong> (non-enumeration-hardened — security review M1): load
 * {@code operator_pin} (RLS-scoped) — a MISSING row runs a DECOY Argon2 verify and is then treated
 * identically to a wrong PIN ({@link InvalidOperatorPinException}, uniform 401), so neither the
 * response nor the timing can discover which employees have a PIN configured; if locked, reject
 * ({@link OperatorPinLockedException}, 423); verify the PIN (constant-time via {@link
 * PasswordEncoder}); on mismatch, record the failed attempt (persisting the lockout counter) and
 * reject uniformly (401). ONLY on a correct PIN do the outlet-assignment ({@link
 * OperatorNotAssignedException}, 403) and login-link ({@link OperatorNotLinkedException}, 409)
 * checks run — so a wrong PIN can never leak assignment status or PIN existence — after which the
 * lockout resets, the role resolves, and the token is minted.
 *
 * <p><strong>Per-outlet operator-PIN policy (ADR 0049).</strong> The FIRST thing this method does
 * is resolve {@code businessId}'s policy via {@link OutletOperatorPolicyReader} (RLS-scoped,
 * defaulting to {@code true} — PIN required — when no row exists). When the policy requires a PIN,
 * the verification flow above runs unchanged. When it does not, the ENTIRE PIN branch is skipped —
 * no PIN load, no lockout check, no Argon2 verify, no decoy — and execution proceeds straight to
 * the outlet-assignment / login-link / role checks below, exactly as it would after a successful
 * PIN today. A caller-supplied {@code pin} is ignored either way in no-PIN mode. The token minted
 * at the end is byte-identical in shape regardless of which branch ran.
 *
 * <p><strong>The seller is taken from this token, never the request body</strong> (rule 5) — the
 * minted {@code operatorUserId} is the employee's OWN linked Keycloak subject id ({@link
 * Employee#getUserId()}), resolved server-side from the verified {@code employeeId}, never a
 * client-supplied value.
 *
 * <p><strong>{@code noRollbackFor = InvalidOperatorPinException.class} is load-bearing.</strong>
 * Spring's default is to roll back a {@code @Transactional} method on ANY unchecked exception — so
 * without this, the {@link #verifyAndMint} mismatch branch would persist {@code
 * recordFailedAttempt} to the {@code EntityManager} via {@link
 * id.co.nativeapp.employee.operator.repository.OperatorPinRepository#flush()} and then, on
 * returning control to the proxy with {@link InvalidOperatorPinException} propagating, have that
 * exact write silently ROLLED BACK — the lockout counter would never actually persist and the
 * brute-force backstop would be a no-op. Declaring this one exception non-rollback lets the write
 * commit while the exception still propagates to the controller/advice unchanged.
 */
@Component
public class OperatorSessionWriter {

  private final OperatorPinRepository operatorPinRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmployeeRepository employeeRepository;
  private final OutletOperatorPolicyReader outletOperatorPolicyReader;
  private final PasswordEncoder operatorPinEncoder;
  private final OperatorTokenSigningKey signingKey;
  private final Clock clock;

  /**
   * A fixed decoy Argon2 hash, computed once. The "no PIN row" branch verifies the submitted PIN
   * against this so it costs the same (a full Argon2 verify) as a wrong-PIN attempt — closing the
   * timing/response oracle that would otherwise reveal which employees have a PIN configured (ADR
   * 0049 non-enumeration; security review M1). Its value is irrelevant (the result is ignored); it
   * only needs the encoder's parameters, which come free from {@code encode(...)}.
   */
  private final String decoyPinHash;

  public OperatorSessionWriter(
      OperatorPinRepository operatorPinRepository,
      AssignmentRepository assignmentRepository,
      EmployeeRepository employeeRepository,
      OutletOperatorPolicyReader outletOperatorPolicyReader,
      PasswordEncoder operatorPinEncoder,
      OperatorTokenSigningKey signingKey,
      Clock clock) {
    this.operatorPinRepository = operatorPinRepository;
    this.assignmentRepository = assignmentRepository;
    this.employeeRepository = employeeRepository;
    this.outletOperatorPolicyReader = outletOperatorPolicyReader;
    this.operatorPinEncoder = operatorPinEncoder;
    this.signingKey = signingKey;
    this.clock = clock;
    this.decoyPinHash = operatorPinEncoder.encode("operator-pin-decoy");
  }

  /**
   * Verifies {@code (employeeId, pin)} for the given outlet and, on success, mints a signed
   * operator token.
   *
   * @throws InvalidOperatorPinException no PIN is set for the employee, or the PIN did not match (→
   *     401, uniform message — no enumeration)
   * @throws OperatorPinLockedException the PIN is currently locked out (→ 423)
   * @throws OperatorNotAssignedException the employee is not actively assigned to {@code
   *     businessId} (→ 403)
   * @throws OperatorNotLinkedException the employee has no linked console login, so no {@code sub}
   *     exists to stamp as the token's {@code operatorUserId} (→ 409)
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = InvalidOperatorPinException.class)
  public OperatorSessionResponse verifyAndMint(UUID businessId, UUID employeeId, String pin) {
    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    LocalDate asOf = LocalDate.now(clock);

    boolean requirePin = outletOperatorPolicyReader.requirePin(businessId);

    if (requirePin) {
      // Normalize a missing/blank pin to "" so it flows into the SAME Argon2 verify calls below as
      // any other wrong value — this is what makes "pin is null/blank" indistinguishable from "pin
      // is wrong" (both land on the uniform 401 InvalidOperatorPinException), rather than a
      // separate validation-level 400 (ADR 0049 non-enumeration).
      String submittedPin = pin == null ? "" : pin;

      OperatorPin operatorPin = operatorPinRepository.findByEmployeeId(employeeId).orElse(null);
      if (operatorPin == null) {
        // No PIN row for this employee (or invisible under RLS to this tenant): run a decoy Argon2
        // verify so this path costs the same as a wrong-PIN attempt, then fail with the SAME
        // uniform 401. A caller therefore cannot tell "no PIN set" from "wrong PIN" by response or
        // timing (ADR 0049 non-enumeration; security review M1). The result is deliberately
        // ignored.
        operatorPinEncoder.matches(submittedPin, decoyPinHash);
        throw new InvalidOperatorPinException();
      }

      if (operatorPin.isLocked(now)) {
        throw new OperatorPinLockedException(employeeId);
      }

      if (!operatorPinEncoder.matches(submittedPin, operatorPin.getPinHash())) {
        operatorPin.recordFailedAttempt(now);
        operatorPinRepository.flush();
        throw new InvalidOperatorPinException();
      }

      operatorPin.recordSuccessfulVerification();
      operatorPinRepository.flush();
    }
    // else: the outlet's policy trusts the employee-pick alone (ADR 0049) — the entire PIN branch
    // above is skipped; any supplied pin is ignored. The checks below run IDENTICALLY either way.

    // ONLY NOW check outlet-assignment and login-link (after a correct PIN in PIN-mode, or
    // unconditionally in no-PIN mode). Running these AFTER the PIN verify in PIN-mode means a wrong
    // PIN can never reveal whether the employee is assigned to this outlet or has a PIN at all
    // (security review M1); a 403/409 is disclosed only to a caller that has already proved the PIN
    // (or an outlet that never required one).
    if (!assignmentRepository.existsActiveAssignment(employeeId, businessId, asOf)) {
      throw new OperatorNotAssignedException(employeeId, businessId);
    }

    Employee employee =
        employeeRepository
            .findById(employeeId)
            .orElseThrow(() -> new OperatorNotAssignedException(employeeId, businessId));

    if (employee.getUserId() == null || employee.getUserId().isBlank()) {
      throw new OperatorNotLinkedException(employeeId);
    }

    String role =
        currentRole(employeeId, businessId, asOf)
            .orElseThrow(() -> new OperatorNotAssignedException(employeeId, businessId));

    Instant exp = now.plus(signingKey.tokenTtl());
    OperatorTokenPayload payload =
        new OperatorTokenPayload(
            OperatorTokenPayload.CURRENT_VERSION,
            companyId,
            businessId,
            employee.getUserId(),
            employeeId,
            employee.getFullName(),
            role,
            now.getEpochSecond(),
            exp.getEpochSecond(),
            UUID.randomUUID().toString());

    String token = OperatorTokenCodec.encode(payload, signingKey.secretBytes());
    return new OperatorSessionResponse(token, employee.getFullName(), role, exp);
  }

  /** The role on the employee's currently-effective assignment to {@code businessId}, if any. */
  private Optional<String> currentRole(UUID employeeId, UUID businessId, LocalDate asOf) {
    List<Assignment> assignments = assignmentRepository.findByEmployeeId(employeeId);
    return assignments.stream()
        .filter(a -> a.getOrgUnitId().equals(businessId))
        .filter(a -> !a.getEffectiveFrom().isAfter(asOf) && !a.getEffectiveTo().isBefore(asOf))
        .map(Assignment::getRole)
        .findFirst();
  }
}
