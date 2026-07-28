package id.co.nativeapp.org.company.controller;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitRequest;
import id.co.nativeapp.org.company.dto.OrgUnitListResponse;
import id.co.nativeapp.org.company.dto.OrgUnitResponse;
import id.co.nativeapp.org.company.dto.PatchOrgUnitCommand;
import id.co.nativeapp.org.company.dto.PatchOrgUnitRequest;
import id.co.nativeapp.org.company.service.OrgUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/org-units} — manage the full org tree (business_unit &gt; outlet &gt; team) under
 * the bound company.
 *
 * <p>Both endpoints are tenant-scoped: the gateway/{@code DevTenantFilter} binds the tenant from
 * the validated JWT before the handler runs, so RLS scopes every lookup to the bound company and
 * {@code company_id} is stamped from that scope, never from the body (rule 5).
 *
 * <ul>
 *   <li>{@code POST /api/v1/org-units} — create a node under an optional parent. Returns {@code
 *       201} + {@code Location}. The parent→child type rule, the same-company parent constraint,
 *       and cycle-prevention are all enforced below (a violation → {@code 400} ProblemDetail).
 *   <li>{@code PATCH /api/v1/org-units/{orgUnitId}} — rename, move, and/or deactivate a node.
 *       Returns {@code 200} with the node's new state.
 * </ul>
 *
 * <p>It is a thin HTTP adapter: it maps the {@code *Request} to an application {@code *Command},
 * calls exactly one service method, and maps the result to an {@link OrgUnitResponse} DTO — never
 * an entity on the wire (DTO-at-the-boundary).
 */
@Tag(name = "Org units", description = "Manage the org tree (business unit > outlet > team)")
@RestController
@RequestMapping("/api/v1/org-units")
public class OrgUnitController {

  private final OrgUnitService orgUnitService;

  public OrgUnitController(OrgUnitService orgUnitService) {
    this.orgUnitService = orgUnitService;
  }

  /**
   * Returns the full org tree for the bound company as a deterministically ordered flat list (by
   * {@code effective_from}, then {@code id}). The frontend assembles the tree from this flat list
   * keyed on {@code id} and {@code parentId}. Includes both active and inactive units — the page
   * shows deactivate/reactivate state. RLS scopes the read to the bound company (rule 5); no {@code
   * WHERE company_id} is in the query.
   *
   * <p>Note: {@code ORDER BY effective_from, id} is a deterministic row ordering, not a topological
   * parent-before-child guarantee; the frontend builds the tree by {@code parentId} lookup.
   */
  @Operation(
      summary = "List all org units (flat, deterministically ordered)",
      description =
          "Returns all org units for the bound company as a flat list ordered deterministically"
              + " (effective_from then id). The frontend builds the tree by parentId. Includes"
              + " active and inactive nodes. 200 always (empty list when no org units exist)."
              + " RLS-scoped (rule 5).")
  @GetMapping
  public ResponseEntity<List<OrgUnitListResponse>> listOrgUnits() {
    return ResponseEntity.ok(orgUnitService.findAllForCurrentTenant());
  }

  /** Create an org unit under an optional parent; emits {@code OrgUnitCreated}. */
  @Operation(
      summary = "Create an org unit",
      description =
          "Creates a node under an optional parent (parent->child type, same-company, and"
              + " cycle-prevention rules enforced; a violation is a 400). Emits OrgUnitCreated."
              + " Returns 201 with a Location header.")
  @PostMapping
  public ResponseEntity<OrgUnitResponse> createOrgUnit(
      @Valid @RequestBody CreateOrgUnitRequest request) {
    CreateOrgUnitCommand command =
        new CreateOrgUnitCommand(
            request.name(), request.type(), request.parentId(), request.vertical());
    OrgUnit created = orgUnitService.create(command);
    OrgUnitResponse body = OrgUnitResponse.from(created);
    return ResponseEntity.created(URI.create("/api/v1/org-units/" + body.id())).body(body);
  }

  /**
   * Rename, move, deactivate, and/or reactivate an org unit; emits {@code OrgUnitChanged} per
   * change. Deactivation cascades to the active subtree; reactivation requires an active parent;
   * deactivate + reactivate together is a {@code 400}.
   */
  @Operation(
      summary = "Update an org unit (rename / move / (de)activate)",
      description =
          "Renames, moves, deactivates, and/or reactivates a node; emits OrgUnitChanged per change."
              + " Deactivation cascades to the active subtree; reactivation requires an active"
              + " parent; a no-op or deactivate+reactivate together is a 400. Returns 200.")
  @PatchMapping("/{orgUnitId}")
  public ResponseEntity<OrgUnitResponse> patchOrgUnit(
      @PathVariable UUID orgUnitId, @Valid @RequestBody PatchOrgUnitRequest request) {
    if (request.isNoOp()) {
      throw new IllegalArgumentException(
          "Patch requested no change (rename, move, deactivate, or reactivate)");
    }
    PatchOrgUnitCommand command =
        new PatchOrgUnitCommand(
            orgUnitId,
            request.name(),
            request.reparent(),
            request.parentId(),
            request.deactivate(),
            request.reactivate());
    OrgUnit patched = orgUnitService.patch(command);
    return ResponseEntity.ok(OrgUnitResponse.from(patched));
  }
}
