package id.co.nativeapp.org.group.controller;

import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.AddMemberRequest;
import id.co.nativeapp.org.group.dto.AddMemberResult;
import id.co.nativeapp.org.group.dto.ConsolidationGroupListResponse;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.dto.CreateGroupRequest;
import id.co.nativeapp.org.group.dto.GroupMembershipListResponse;
import id.co.nativeapp.org.group.dto.GroupResponse;
import id.co.nativeapp.org.group.dto.MembershipResponse;
import id.co.nativeapp.org.group.dto.RemoveMemberCommand;
import id.co.nativeapp.org.group.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/consolidation-groups} — define a consolidation group and manage its membership
 * (P3d SEAM 1). Every endpoint is tenant-scoped: the gateway/{@code DevTenantFilter} binds the
 * tenant from the validated JWT before the handler runs, so the group is owned by the caller (the
 * LEAD company), RLS scopes every lookup to it, and {@code lead_company_id}/{@code company_id} is
 * stamped from that scope, never from the body (rule 5). A member company therefore cannot define
 * or mutate a group it does not lead.
 *
 * <ul>
 *   <li>{@code POST /api/v1/consolidation-groups} — define a group; sets the lead = the caller, the
 *       reporting currency once. Returns {@code 201} + {@code Location}; emits {@code
 *       GroupDefined}.
 *   <li>{@code POST /api/v1/consolidation-groups/{groupId}/members} — add a member. Returns {@code
 *       201} + {@code Location} on a genuinely new membership, or {@code 200} (no {@code Location})
 *       on an idempotent no-op re-add of an already-ACTIVE member; emits {@code
 *       GroupMembershipChanged(ADDED)} only on a real add.
 *   <li>{@code DELETE /api/v1/consolidation-groups/{groupId}/members/{memberCompanyId}} — remove a
 *       member by CLOSING its effective window (no hard-delete). Returns {@code 200} with the
 *       closed membership, or {@code 204} when there was no active membership to close; emits
 *       {@code GroupMembershipChanged(REMOVED)} on a real removal.
 * </ul>
 *
 * <p>It is a thin HTTP adapter: it maps the {@code *Request} to an application {@code *Command},
 * calls exactly one service method, and maps the result to a DTO — never an entity on the wire
 * (DTO-at-the-boundary).
 */
@Tag(
    name = "Consolidation groups",
    description = "Define a consolidation group (led by the caller) and manage its membership")
@RestController
@RequestMapping("/api/v1/consolidation-groups")
public class GroupController {

  private final GroupService groupService;

  public GroupController(GroupService groupService) {
    this.groupService = groupService;
  }

  /**
   * Lists all consolidation groups the bound tenant leads. RLS scopes the read to the bound
   * company (rule 5); no {@code WHERE company_id} is in the query. Returns {@code 200} with an
   * empty list when the company leads no groups.
   */
  @Operation(
      summary = "List consolidation groups led by the current company",
      description =
          "Returns all consolidation groups for which the bound company is the lead. 200 always"
              + " (empty list when the company leads no groups). RLS-scoped (rule 5).")
  @GetMapping
  public ResponseEntity<List<ConsolidationGroupListResponse>> listGroups() {
    return ResponseEntity.ok(groupService.findAllGroupsForCurrentTenant());
  }

  /**
   * Lists all members of a consolidation group the bound tenant leads. RLS scopes the read to the
   * bound company, so a group the caller does not lead (or an unknown group id) returns an empty
   * list — mapped to {@code 404}. Returns {@code 200} with the member list (may be empty when the
   * group has no members yet).
   *
   * <p>The convention for "group not led by caller": the sibling DELETE path returns {@code 204}
   * (idempotent re-remove of nothing), but a GET for members of a non-existent group returns
   * {@code 404} — the caller explicitly requested a resource that does not exist.
   */
  @Operation(
      summary = "List members of a consolidation group",
      description =
          "Returns all members of the given group (which the bound company must lead). 200 with the"
              + " member list; 404 when the group does not exist or the caller does not lead it"
              + " (RLS makes non-lead groups invisible — fail closed). RLS-scoped (rule 5).")
  @GetMapping("/{groupId}/members")
  public ResponseEntity<List<GroupMembershipListResponse>> listMembers(
      @PathVariable UUID groupId) {
    List<GroupMembershipListResponse> members = groupService.findMembersForGroup(groupId);
    // Treat an empty result as "group not found or not led by caller" — 404.
    // A group with genuine zero members would also be empty; that is acceptable because zero-member
    // groups carry no consolidation meaning. If the product evolves to support empty groups,
    // add a group-existence check here.
    if (members.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(members);
  }

  /** Define a consolidation group led by the caller; emits {@code GroupDefined}. */
  @Operation(
      summary = "Define a consolidation group",
      description =
          "Defines a group led by the caller (the lead company), fixing its reporting currency"
              + " once; emits GroupDefined. Returns 201 with a Location header.")
  @PostMapping
  public ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request) {
    CreateGroupCommand command =
        new CreateGroupCommand(request.name(), request.reportingCurrency());
    ConsolidationGroup created = groupService.createGroup(command);
    GroupResponse body = GroupResponse.from(created);
    return ResponseEntity.created(URI.create("/api/v1/consolidation-groups/" + body.id()))
        .body(body);
  }

  /**
   * Add a member company to a group the caller leads; emits {@code GroupMembershipChanged(ADDED)}.
   *
   * <p>Returns {@code 201 Created} + {@code Location} for a genuinely new membership. An idempotent
   * no-op re-add of an already-ACTIVE member is NOT a creation, so it returns {@code 200 OK} (the
   * existing membership) with no {@code Location} — nothing was created.
   */
  @Operation(
      summary = "Add a member to a group",
      description =
          "Adds a member company to a group the caller leads; emits GroupMembershipChanged(ADDED)."
              + " Returns 201 with a Location for a new membership, or 200 (no Location) on an"
              + " idempotent re-add of an already-active member.")
  @PostMapping("/{groupId}/members")
  public ResponseEntity<MembershipResponse> addMember(
      @PathVariable UUID groupId, @Valid @RequestBody AddMemberRequest request) {
    AddMemberCommand command = new AddMemberCommand(groupId, request.memberCompanyId());
    AddMemberResult result = groupService.addMember(command);
    MembershipResponse body = MembershipResponse.from(result.membership());
    if (!result.created()) {
      // Idempotent no-op: the company was already an ACTIVE member — 200, not 201, no Location.
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(
            URI.create(
                "/api/v1/consolidation-groups/" + groupId + "/members/" + body.memberCompanyId()))
        .body(body);
  }

  /**
   * Remove a member company from a group the caller leads by closing its effective window; emits
   * {@code GroupMembershipChanged(REMOVED)} on a real removal. {@code 204} when there was no active
   * membership to close (idempotent re-remove).
   */
  @Operation(
      summary = "Remove a member from a group",
      description =
          "Removes a member company from a group the caller leads by closing its effective window"
              + " (no hard-delete); emits GroupMembershipChanged(REMOVED) on a real removal. 200"
              + " with the closed membership, or 204 when there was no active membership to close.")
  @DeleteMapping("/{groupId}/members/{memberCompanyId}")
  public ResponseEntity<MembershipResponse> removeMember(
      @PathVariable UUID groupId, @PathVariable UUID memberCompanyId) {
    RemoveMemberCommand command = new RemoveMemberCommand(groupId, memberCompanyId);
    GroupMembership closed = groupService.removeMember(command);
    if (closed == null) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok(MembershipResponse.from(closed));
  }
}
