package id.co.nativeapp.org.user.controller;

import id.co.nativeapp.org.user.dto.ReplaceUserOutletsRequest;
import id.co.nativeapp.org.user.dto.UserOutletAssignmentResponse;
import id.co.nativeapp.org.user.service.UserOutletAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users/{userId}/outlets} and {@code /api/v1/users/me/outlets} — user ↔ outlet
 * assignment management.
 *
 * <p>The addressed-by-id endpoints ({@code /{userId}/outlets}) require {@code owner} or {@code
 * manager} role (enforced at the gateway by the {@code usersRoute} bean with {@code
 * DASHBOARD_ROLES}). The {@code /me/outlets} endpoint allows all three roles including {@code
 * cashier} (enforced by the dedicated {@code userMeOutletsRoute} gateway bean, which must be
 * ordered before the general {@code usersRoute} so it matches first — see {@code RoutingConfig}).
 *
 * <p><strong>Cross-tenant guard.</strong> Addressed-by-id operations resolve the target user in the
 * caller's tenant and return 404 if absent or cross-tenant (anti-enumeration — same contract as
 * {@link UserController}).
 *
 * <p><strong>PUT semantics.</strong> {@code PUT /{userId}/outlets} is a replace-set: it atomically
 * replaces the user's complete outlet assignment with the submitted list. An empty list removes all
 * assignments. Idempotent: submitting the current set is a no-op.
 *
 * <p>Tenant is derived from the JWT via the gateway's {@link
 * id.co.nativeapp.gateway.filter.TenantContextHeaderFilter} — never from the request body or a path
 * parameter (rule 5).
 */
@Tag(
    name = "User Outlet Assignments",
    description =
        "Outlet assignments per console user — owner/manager can manage, cashier can read own")
@RestController
@RequestMapping("/api/v1/users")
public class UserOutletAssignmentController {

  private final UserOutletAssignmentService outletAssignmentService;

  public UserOutletAssignmentController(UserOutletAssignmentService outletAssignmentService) {
    this.outletAssignmentService = outletAssignmentService;
  }

  /**
   * Returns the caller's own active outlet assignments for the POS outlet picker intersection.
   *
   * <p>Resolves the caller's identity from {@link id.co.nativeapp.tenant.TenantContext} (the {@code
   * X-Actor} header injected by the gateway — the JWT {@code sub}). Allowed for all three roles
   * (owner/manager/cashier); the gateway routes {@code /api/v1/users/me/outlets} with {@code
   * POS_ROLES} and must be ordered before the general {@code /api/v1/users/**} route so the more
   * specific path matches first.
   *
   * @return {@code 200} with the active outlet assignments for the caller; empty list when none
   */
  @Operation(
      summary = "Get the caller's own outlet assignments (POS picker)",
      description =
          "Returns active outlet assignments for the authenticated caller (resolved from the JWT"
              + " sub). Allowed for owner, manager, and cashier — the POS uses this to intersect"
              + " the picker with the cashier's assigned outlets. 200 always (empty list when"
              + " unassigned). Route /me/outlets must be ordered before /users/** at the gateway.")
  @GetMapping("/me/outlets")
  public ResponseEntity<List<UserOutletAssignmentResponse>> listMyOutlets() {
    return ResponseEntity.ok(outletAssignmentService.listOutletsForMe());
  }

  /**
   * Returns the active outlet assignments for a specific user (owner/manager only).
   *
   * <p>Enforces the cross-tenant guard — returns 404 if the user is absent or belongs to a
   * different company. An empty list is returned (not 404) when the user exists but has no active
   * assignments.
   *
   * @param userId the target user's Keycloak UUID
   * @return {@code 200} with the active outlet assignments; empty list when unassigned
   */
  @Operation(
      summary = "List outlet assignments for a user",
      description =
          "Returns active outlet assignments for the given user (joined to org_unit for names)."
              + " Cross-tenant targets return 404 (anti-enumeration). Requires owner or manager"
              + " role (enforced at the gateway on the /users/** route).")
  @GetMapping("/{userId}/outlets")
  public ResponseEntity<List<UserOutletAssignmentResponse>> listOutlets(
      @PathVariable String userId) {
    return ResponseEntity.ok(outletAssignmentService.listOutletsForUser(userId));
  }

  /**
   * Atomically replaces the outlet assignment set for a user (PUT semantics — owner/manager only).
   *
   * <p>Validates all submitted org-unit ids before writing (all-or-nothing): each must be an active
   * {@code OUTLET} in the caller's tenant — a non-OUTLET or inactive unit returns {@code 400}. An
   * empty {@code orgUnitIds} list removes all assignments. Idempotent: submitting the current set
   * is a no-op. Returns the resulting active assignment list after the write.
   *
   * @param userId the target user's Keycloak UUID
   * @param request the desired complete set of outlet org-unit UUIDs
   * @return {@code 200} with the resulting active outlet assignments
   */
  @Operation(
      summary = "Replace outlet assignments for a user (PUT / replace-set)",
      description =
          "Atomically replaces the user's complete outlet assignment set. All org-unit ids are"
              + " validated before writing — each must be an active OUTLET in the caller's tenant"
              + " (400 on any invalid id). An empty orgUnitIds removes all. Idempotent (same set ="
              + " no-op). Returns the resulting active list. Requires owner or manager role.")
  @PutMapping("/{userId}/outlets")
  public ResponseEntity<List<UserOutletAssignmentResponse>> replaceOutlets(
      @PathVariable String userId, @Valid @RequestBody ReplaceUserOutletsRequest request) {
    return ResponseEntity.ok(
        outletAssignmentService.replaceOutletsForUser(userId, request.orgUnitIds()));
  }
}
