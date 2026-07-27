package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.repository.OrgUnitRepository;
import id.co.nativeapp.org.user.domain.UserOutletAssignment;
import id.co.nativeapp.org.user.repository.UserOutletAssignmentRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write path for user ↔ outlet assignments.
 *
 * <p>A distinct {@code @Component} (not private methods on {@link UserOutletAssignmentService}) so
 * every method is invoked through the Spring proxy and the {@link RlsAutoApplyAspect} sets the
 * tenant GUC (rule 5) — the same {@code *Writer} pattern that {@link
 * id.co.nativeapp.org.company.service.OrgUnitWriter} uses.
 *
 * <p><strong>PUT (replace-set) semantics.</strong> {@link #replaceAssignments} atomically replaces
 * a user's complete outlet assignment set:
 *
 * <ul>
 *   <li>Outlets in the new set but not currently active → <em>insert</em> a fresh row (or
 *       <em>reopen</em> an existing inactive row).
 *   <li>Outlets currently active but not in the new set → <em>close</em> the row ({@code
 *       effective_to = today, active = false}).
 *   <li>Outlets in both the current and new set → <em>no-op</em> (idempotent).
 * </ul>
 *
 * <p><strong>Validation (all-or-nothing).</strong> Every outlet id is validated before any row is
 * written — each must resolve RLS-scoped to an active org unit of type {@code OUTLET}. A single
 * invalid id aborts the whole operation with a 400.
 *
 * <p><strong>PII / observability.</strong> User ids (Keycloak subs) are stable, non-PII identifiers
 * and are safe to log. Outlet names are not logged — only UUIDs appear in log statements.
 */
@Component
public class UserOutletAssignmentWriter {

  private static final Logger log = LoggerFactory.getLogger(UserOutletAssignmentWriter.class);

  private final UserOutletAssignmentRepository assignmentRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final Clock clock;

  public UserOutletAssignmentWriter(
      UserOutletAssignmentRepository assignmentRepository,
      OrgUnitRepository orgUnitRepository,
      Clock clock) {
    this.assignmentRepository = assignmentRepository;
    this.orgUnitRepository = orgUnitRepository;
    this.clock = clock;
  }

  /**
   * Atomically replaces the outlet assignment set for {@code userId} with the provided {@code
   * desiredOutletIds}.
   *
   * <p>Validates ALL outlet ids before writing anything (all-or-nothing): every id must resolve
   * RLS-scoped to an active org unit of type {@code OUTLET}. A single invalid id throws {@link
   * InvalidOutletAssignmentException} (400).
   *
   * @param userId the Keycloak subject id of the target user
   * @param desiredOutletIds the desired complete set of outlet org-unit UUIDs (may be empty)
   * @throws InvalidOutletAssignmentException if any id is not an active OUTLET in the caller's
   *     tenant (400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void replaceAssignments(String userId, List<UUID> desiredOutletIds) {
    TenantContext.require(); // assert tenant is bound — RLS aspect uses it

    // De-duplicate the request: PUT is idempotent; repeated ids in the body are the same intent.
    Set<UUID> desired = new HashSet<>(desiredOutletIds);

    // --- Validate ALL desired outlets before touching any row (all-or-nothing) ---
    validateAllAreActiveOutlets(desired);

    LocalDate today = LocalDate.now(clock);

    // Load all existing rows for this user (active + inactive) — we reopen inactive rows
    // that appear in the new desired set rather than creating duplicate rows (UNIQUE constraint).
    List<UserOutletAssignment> existing = assignmentRepository.findAllByUserId(userId);
    Map<UUID, UserOutletAssignment> existingByOutlet =
        existing.stream().collect(Collectors.toMap(UserOutletAssignment::getOrgUnitId, a -> a));

    // 1. Close rows that are currently active but not in the desired set.
    for (UserOutletAssignment assignment : existing) {
      if (assignment.isActive() && !desired.contains(assignment.getOrgUnitId())) {
        assignment.close(today);
        assignmentRepository.save(assignment);
        log.info(
            "Closed outlet assignment: userId={}, orgUnitId={}", userId, assignment.getOrgUnitId());
      }
    }

    // 2. For each desired outlet: reopen an existing inactive row or insert a new one.
    for (UUID outletId : desired) {
      UserOutletAssignment existing_ = existingByOutlet.get(outletId);
      if (existing_ == null) {
        // No prior row — insert a fresh assignment.
        UserOutletAssignment newAssignment = new UserOutletAssignment(userId, outletId, today);
        newAssignment.setCompanyId(TenantContext.require().companyId());
        assignmentRepository.save(newAssignment);
        log.info("Inserted outlet assignment: userId={}, orgUnitId={}", userId, outletId);
      } else if (!existing_.isActive()) {
        // Pre-existing inactive row — reopen it (preserves history, respects UNIQUE constraint).
        existing_.reopen();
        assignmentRepository.save(existing_);
        log.info("Reopened outlet assignment: userId={}, orgUnitId={}", userId, outletId);
      }
      // else: already active → no-op (idempotent)
    }

    assignmentRepository.flush();
  }

  /**
   * Validates that every id in {@code desiredOutletIds} resolves RLS-scoped to an active org unit
   * of type {@code OUTLET}. Throws {@link InvalidOutletAssignmentException} on the first invalid
   * id. Called before any write so the operation is all-or-nothing.
   */
  private void validateAllAreActiveOutlets(Set<UUID> desiredOutletIds) {
    for (UUID id : desiredOutletIds) {
      OrgUnit unit =
          orgUnitRepository
              .findById(id)
              .orElseThrow(
                  () ->
                      new InvalidOutletAssignmentException(
                          id, "org unit not found or not in the caller's tenant"));

      if (!OrgUnitType.OUTLET.equals(unit.getType())) {
        throw new InvalidOutletAssignmentException(
            id, "org unit is not of type OUTLET (got " + unit.getType() + ")");
      }
      if (!unit.isActive()) {
        throw new InvalidOutletAssignmentException(id, "org unit is not active");
      }
    }
  }
}
