package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.projection.UserOutletAssignmentView;
import id.co.nativeapp.org.user.repository.UserOutletAssignmentRepository;
import id.co.nativeapp.org.user.repository.UserOutletAssignmentRepository.UserOutletCountView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only transactional bean for the outlet-assignment read path.
 *
 * <p>A separate {@code @Component} (not private methods on {@link UserOutletAssignmentService}) so
 * the read-only transaction is invoked through the Spring proxy — a self-invocation would bypass
 * the {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC,
 * which is the load-bearing mechanism that scopes queries to the bound company (rule 5).
 *
 * <p>{@code readOnly = true} lets the PostgreSQL driver skip acquiring a write lock and allows a
 * replica to serve the read.
 */
@Component
public class UserOutletAssignmentReader {

  private final UserOutletAssignmentRepository assignmentRepository;

  public UserOutletAssignmentReader(UserOutletAssignmentRepository assignmentRepository) {
    this.assignmentRepository = assignmentRepository;
  }

  /**
   * Active outlet assignments for the given user, joined to {@code org_unit} for the name. No
   * {@code WHERE company_id} — RLS constrains the result to the bound company (rule 5). Uses the
   * {@link UserOutletAssignmentView} projection — never {@code SELECT *} of the entity (§3.3).
   *
   * @param userId the Keycloak subject id of the user
   * @return the active outlet assignments, ordered by outlet name; empty list when none
   */
  @Transactional(readOnly = true)
  public List<UserOutletAssignmentView> findActiveByUserId(String userId) {
    return assignmentRepository.findActiveByUserId(userId);
  }

  /**
   * Outlet counts per user id for the provided list of user ids. Used by the Team list endpoint to
   * enrich each row with {@code outletCount} in one query (no N+1). Returns a map of {@code userId
   * → outletCount}; users with zero assignments are absent from the map (callers default to 0). No
   * {@code WHERE company_id} — RLS constrains the result (rule 5).
   *
   * <p>The caller must chunk the list to ≤ 1 000 entries before calling here (CLAUDE.md).
   *
   * @param userIds the Keycloak subject ids to count assignments for
   * @return map of userId → count; absent keys mean zero
   */
  @Transactional(readOnly = true)
  public Map<String, Long> countActiveByUserIds(List<String> userIds) {
    return assignmentRepository.countActiveByUserIds(userIds).stream()
        .collect(
            Collectors.toMap(UserOutletCountView::getUserId, UserOutletCountView::getOutletCount));
  }
}
