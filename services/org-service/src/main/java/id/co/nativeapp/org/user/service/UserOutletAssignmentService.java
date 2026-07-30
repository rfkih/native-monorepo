package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.dto.UserOutletAssignmentResponse;
import id.co.nativeapp.org.user.projection.UserOutletAssignmentView;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Business logic for user ↔ outlet assignments.
 *
 * <p>Orchestrates the transactional units of work in {@link UserOutletAssignmentWriter} and the
 * read path in {@link UserOutletAssignmentReader}. The transaction boundary, RLS GUC, and database
 * access all live on the writer/reader beans so the Spring proxy + the auto-RLS aspect engage (the
 * {@code *Writer}/{@code *Reader} pattern — ENGINEERING-STANDARDS §2.5).
 *
 * <p><strong>Cross-tenant guard.</strong> Addressed-by-id operations ({@link
 * #listOutletsForUser(String)} and {@link #replaceOutletsForUser(String, List)}) resolve the target
 * user via {@link KeycloakAdminClient#getUserById(String)}, then verify the user's {@code
 * company_id} attribute matches the caller's tenant from {@link TenantContext#require()}. If it
 * does not match, {@link UserNotFoundException} (404) is thrown — identical behaviour and error
 * shape as {@link UserService#getUserById(String)} (anti-enumeration: caller cannot distinguish
 * "not found" from "different tenant").
 *
 * <p><strong>NOT {@code @Transactional}.</strong> The service itself carries no transaction; the
 * writer/reader beans own the transaction boundaries (CODE-STRUCTURE §3.1 — {@code @Transactional}
 * only on service-layer beans with separate {@code *Writer} components for {@code REQUIRES_NEW}).
 */
@Service
public class UserOutletAssignmentService {

  private final KeycloakAdminClient keycloak;
  private final UserOutletAssignmentWriter writer;
  private final UserOutletAssignmentReader reader;

  public UserOutletAssignmentService(
      KeycloakAdminClient keycloak,
      UserOutletAssignmentWriter writer,
      UserOutletAssignmentReader reader) {
    this.keycloak = keycloak;
    this.writer = writer;
    this.reader = reader;
  }

  // ---------------------------------------------------------------------------
  // List outlets for a specific user (GET /users/{userId}/outlets)
  // ---------------------------------------------------------------------------

  /**
   * Returns the active outlet assignments for the given user, enforcing the cross-tenant guard.
   *
   * <p>The cross-tenant guard resolves the target user in the caller's tenant and returns 404 if
   * absent or cross-tenant (anti-enumeration).
   *
   * @param userId the Keycloak subject id from the path
   * @return the active outlet assignments for the user, ordered by outlet name
   * @throws UserNotFoundException if the user is absent or belongs to a different tenant (404)
   */
  public List<UserOutletAssignmentResponse> listOutletsForUser(String userId) {
    TenantContext.require();
    resolveInTenant(userId); // cross-tenant guard
    return toResponses(reader.findActiveByUserId(userId));
  }

  // ---------------------------------------------------------------------------
  // List outlets for the calling user themselves (GET /users/me/outlets)
  // ---------------------------------------------------------------------------

  /**
   * Returns the active outlet assignments for the authenticated caller themselves.
   *
   * <p>The caller's Keycloak subject id is taken from {@link TenantContext.Tenant#actor()} — the
   * JWT {@code sub} the gateway injected as {@code X-Actor}. No cross-tenant guard needed: a user
   * reading their own assignments cannot enumerate other users.
   *
   * @return the active outlet assignments for the caller, ordered by outlet name
   */
  public List<UserOutletAssignmentResponse> listOutletsForMe() {
    String callerId = TenantContext.require().actor();
    return toResponses(reader.findActiveByUserId(callerId));
  }

  // ---------------------------------------------------------------------------
  // Replace outlet assignments (PUT /users/{userId}/outlets)
  // ---------------------------------------------------------------------------

  /**
   * Atomically replaces the outlet assignment set for the given user (PUT semantics).
   *
   * <p>The cross-tenant guard is enforced first. All outlet ids are validated before any write
   * (all-or-nothing). Returns the resulting active assignment list.
   *
   * @param userId the Keycloak subject id of the target user
   * @param orgUnitIds the desired complete set of outlet org-unit UUIDs (empty = remove all)
   * @return the resulting active outlet assignments, ordered by outlet name
   * @throws UserNotFoundException if the user is absent or belongs to a different tenant (404)
   * @throws InvalidOutletAssignmentException if any org-unit id is not an active OUTLET (400)
   */
  public List<UserOutletAssignmentResponse> replaceOutletsForUser(
      String userId, List<UUID> orgUnitIds) {
    TenantContext.require();
    resolveInTenant(userId); // cross-tenant guard
    writer.replaceAssignments(userId, orgUnitIds);
    // Re-read for the authoritative post-write state.
    return toResponses(reader.findActiveByUserId(userId));
  }

  // ---------------------------------------------------------------------------
  // Outlet count enrichment for the Team list (GET /users)
  // ---------------------------------------------------------------------------

  /**
   * Maps a list of user ids to their active outlet counts for enriching the Team list response. No
   * N+1: a single grouped query fetches counts for all ids at once. Callers must chunk to ≤ 1 000
   * ids before calling here (CLAUDE.md IN-clause limit).
   *
   * @param userIds the Keycloak subject ids to count for
   * @return map of userId → outletCount; absent keys mean 0
   */
  public Map<String, Long> outletCountsByUserIds(List<String> userIds) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    return reader.countActiveByUserIds(userIds);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves a user by id and enforces the cross-tenant guard. Returns {@link
   * UserNotFoundException} (404) in ALL other cases — user does not exist, user belongs to a
   * different company. The 404 (not 403) is the anti-enumeration design: a caller cannot learn
   * whether a user id exists in a different tenant. Mirrors the logic in {@link
   * UserService#resolveInTenant}.
   */
  private void resolveInTenant(String userId) {
    String callerCompanyId = TenantContext.require().companyId();
    KeycloakUser user =
        keycloak.getUserById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    // A multi-company login belongs to each of its companies (ADR 0021); 404 anti-enumeration.
    if (!user.belongsTo(callerCompanyId)) {
      throw new UserNotFoundException(userId);
    }
  }

  private static List<UserOutletAssignmentResponse> toResponses(
      List<? extends UserOutletAssignmentView> views) {
    return views.stream()
        .map(v -> new UserOutletAssignmentResponse(v.getOrgUnitId(), v.getName()))
        .toList();
  }
}
