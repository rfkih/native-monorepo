package id.co.nativeapp.finance.consolidation.service;

import id.co.nativeapp.finance.consolidation.domain.GroupAccessDeniedException;
import id.co.nativeapp.finance.consolidation.domain.GroupConsolidationRole;
import id.co.nativeapp.finance.consolidation.domain.GroupRoleDeniedException;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.dto.GroupConsolidationResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.finance.group.service.GroupLeadResolver;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The group consolidation AUTHORIZATION + binding crux (P3d SEAM 4b) — the single place that
 * decides whether a request may reach a group's consolidation, and the ONLY place a group scope is
 * bound for a REQUEST (as opposed to the consumer-driven close in {@link GroupCloseService}).
 *
 * <p><strong>Two gates, both required (AND), before ANY group scope is bound:</strong>
 *
 * <ol>
 *   <li><strong>Group role</strong> — the requester must carry the group-level role for the
 *       operation ({@link GroupConsolidationRole#VIEW} to read, {@link
 *       GroupConsolidationRole#CLOSE} to close), from the JWT roles. No role → {@link
 *       GroupRoleDeniedException} ({@code 403}).
 *   <li><strong>Validated lead binding</strong> — the requester's company (the JWT-bound tenant)
 *       must BE the lead of the requested group, validated against the AUTHORITATIVE {@code
 *       group_lead} read model ({@link GroupLeadResolver}, a cross-tenant reference — never trust a
 *       group id from the token without this check). If the requester company != the resolved lead
 *       (or the group is unknown) → {@link GroupAccessDeniedException} ({@code 404}, to avoid
 *       group-existence disclosure). A user can NEVER bind a group their company does not lead.
 * </ol>
 *
 * <p>ONLY after BOTH gates pass is the group scope bound via {@link
 * TenantContext#callAsGroup}(lead, group), so the two-GUC conjunction RLS engages on the
 * consolidation tables. No validated grant → the group scope is never bound → the group RLS
 * policies match nothing → fail closed. The lead the scope is bound to is the AUTHORITATIVE lead
 * resolved here, never a client-supplied value, so even a forged path/body group id binds the real
 * lead's scope (which the requester provably leads) or is denied.
 *
 * <p>This is the {@link GroupCloseService} pattern (resolve lead → {@code callAsGroup} → delegate),
 * extended with the request-side ENTITLEMENT validation that the consumer path does not need (the
 * consumer is system-driven, not a user request).
 */
@Service
public class GroupConsolidationService {

  private static final Logger log = LoggerFactory.getLogger(GroupConsolidationService.class);

  private final GroupLeadResolver leadResolver;
  private final GroupConsolidationReader reader;
  private final GroupCloseService closeService;

  public GroupConsolidationService(
      GroupLeadResolver leadResolver,
      GroupConsolidationReader reader,
      GroupCloseService closeService) {
    this.leadResolver = leadResolver;
    this.reader = reader;
    this.closeService = closeService;
  }

  /**
   * Reads the group consolidation for a {@code (group, period)} on behalf of {@code requester} —
   * after validating the requester holds {@link GroupConsolidationRole#VIEW} AND leads the group.
   * Returns the group total + flags + eliminations status, or empty if no close exists for the
   * period yet.
   *
   * @throws GroupRoleDeniedException if the requester lacks the read role ({@code 403})
   * @throws GroupAccessDeniedException if the requester's company does not lead the group ({@code
   *     404})
   */
  public Optional<GroupConsolidationResponse> readConsolidation(
      GroupRequester requester, UUID groupId, String period) {
    requireRole(requester, GroupConsolidationRole.VIEW, "read the group consolidation");
    UUID lead = requireLead(requester, groupId);
    return GroupScopeRunner.runInGroupScope(
        lead,
        groupId,
        requester.actor(),
        "Failed to read the group consolidation",
        () -> reader.read(groupId, period));
  }

  /**
   * Triggers a group close for a {@code (group, period)} at {@code closeRunSeq} on behalf of {@code
   * requester} — after validating the requester holds {@link GroupConsolidationRole#CLOSE}
   * (lead-admin) AND leads the group. Delegates to {@link GroupCloseService} (which re-binds the
   * group scope for its own transactional close), and returns the result state.
   *
   * @throws GroupRoleDeniedException if the requester lacks the close role ({@code 403})
   * @throws GroupAccessDeniedException if the requester's company does not lead the group ({@code
   *     404})
   */
  public GroupCloseResponse closeGroup(
      GroupRequester requester, UUID groupId, String period, int closeRunSeq) {
    requireRole(requester, GroupConsolidationRole.CLOSE, "close the group consolidation");
    // Validate the requester leads the group BEFORE delegating, and PASS the validated lead INTO
    // the
    // close (#43) — so the lead the close binds is STRUCTURALLY the one authorized here, not a
    // value
    // re-resolved independently inside the close that could (in principle) diverge from it.
    UUID lead = requireLead(requester, groupId);
    GroupCloseResult result = closeService.closeAs(lead, groupId, period, closeRunSeq);
    return GroupCloseResponse.from(groupId, period, result);
  }

  // ----------------------------------------------------------------------- gates

  /** Gate 1 — the group-level role. No role → 403, raised BEFORE any group scope is bound. */
  private static void requireRole(GroupRequester requester, String role, String action) {
    if (!requester.hasRole(role)) {
      throw new GroupRoleDeniedException(
          "Requester " + requester.companyId() + " lacks the role '" + role + "' to " + action);
    }
  }

  /**
   * Gate 2 — the validated lead binding. Resolves the group's AUTHORITATIVE lead from {@code
   * group_lead} and asserts it equals the requester's company. An unknown group OR a non-lead
   * requester both raise {@link GroupAccessDeniedException} (→ 404), so a member cannot tell a
   * group it does not lead apart from a non-existent one (no group-existence disclosure). Returns
   * the validated lead — the ONLY company the group scope is ever bound to.
   */
  private UUID requireLead(GroupRequester requester, UUID groupId) {
    Optional<UUID> lead = leadResolver.leadOf(groupId);
    if (lead.isEmpty() || !lead.get().equals(requester.companyId())) {
      // Same denial for "no such group" and "you do not lead it" — do not disclose existence.
      log.warn(
          "Group consolidation access DENIED: requester company {} is not the lead of group {}"
              + " (resolved lead present={})",
          requester.companyId(),
          groupId,
          lead.isPresent());
      throw new GroupAccessDeniedException(
          "No group " + groupId + " led by company " + requester.companyId());
    }
    return lead.get();
  }
}
