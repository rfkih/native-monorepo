package id.co.nativeapp.barbershop.outletref.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@code user_outlet_assignment_ref} read model — the local cache of org-service
 * user → outlet assignments used for the ticket write-path enforcement (ported verbatim from
 * carwash-service's {@code outletref} feature).
 *
 * <p>Implemented as a plain {@link JdbcTemplate} wrapper (no Spring Data JPA entity, no full-entity
 * load) because this is a read model, not an aggregate: we need exactly one {@code EXISTS} query on
 * the hot guard path, and a separate count query for the grandfather-clause check. Both run under
 * an active {@link id.co.nativeapp.tenant.TenantContext} so the RLS GUC is set by the auto-RLS
 * aspect ({@link id.co.nativeapp.tenant.RlsAutoApplyAspect}) on the enclosing
 * {@code @Transactional} (rule 5 — the caller's transaction is the unit of work that sets the GUC).
 *
 * <p>Queries are native SQL (CLAUDE.md — all {@code @Query}s must be native; here it is raw JDBC
 * for a read model, same principle). No {@code SELECT *} and no full entity loaded (CLAUDE.md
 * rule).
 */
@Repository
public class UserOutletAssignmentRefRepository {

  private final JdbcTemplate jdbcTemplate;

  public UserOutletAssignmentRefRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * {@code true} if there is at least one ACTIVE assignment row for {@code (userId, orgUnitId)} in
   * the session tenant's scope (RLS applies). This is the hot guard-path query backed by the
   * partial index {@code idx_user_outlet_assignment_ref_active} on {@code (company_id, user_id)
   * WHERE active = TRUE}.
   *
   * @param userId the Keycloak subject id of the attendant/cashier
   * @param orgUnitId the outlet being rung
   */
  public boolean hasActiveAssignment(String userId, UUID orgUnitId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM user_outlet_assignment_ref
             WHERE user_id     = ?
               AND org_unit_id = ?
               AND active      = TRUE
            """,
            Integer.class,
            userId,
            orgUnitId);
    return count != null && count > 0;
  }

  /**
   * The total count of ALL rows (across all users) in {@code user_outlet_assignment_ref} for the
   * session tenant's scope (RLS applies). Used for the grandfather clause: if this returns 0, the
   * company has NEVER adopted outlet scoping (no events delivered yet) and the legacy-allow path
   * applies.
   */
  public long countAllForCompany() {
    Long count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_outlet_assignment_ref", Long.class);
    return count != null ? count : 0L;
  }
}
