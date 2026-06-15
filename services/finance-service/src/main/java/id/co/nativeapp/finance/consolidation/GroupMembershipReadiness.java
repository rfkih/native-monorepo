package id.co.nativeapp.finance.consolidation;

import id.co.nativeapp.finance.group.GroupLeadResolver;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The WARMING-UP guard for the consolidation close (P3d SEAM 3a — the design-critique Critical): is
 * the local {@code group_member} projection CAUGHT UP for a group, so the close can trust the
 * member set it resolves? If a known membership change for the group has NOT yet been consumed, the
 * close must HOLD ({@link ConsolidationState#PENDING_MEMBERS_WARMING_UP}) rather than consolidate a
 * STALE member set — including a member that has since left, or missing one that has since joined.
 *
 * <p><strong>Why a seam, not inline logic.</strong> finance learns of membership purely from the
 * org-service {@code GroupDefined} / {@code GroupMembershipChanged} events (rule 2 — never a sync
 * call). "Caught up" is therefore a property of event-consumption progress, which different
 * deployments may track differently (a consumer-group lag check, a producer-stamped membership
 * watermark on {@code GroupDefined}, an expected-member-count hint). Modelling it as an injectable
 * component keeps the close pipeline's gate explicit and lets the readiness signal evolve without
 * touching the close, and lets a test drive both the ready and the warming-up branches
 * deterministically.
 *
 * <p>The DEFAULT implementation is deliberately conservative-but-non-blocking for the common case:
 * a group whose {@code GroupDefined} has been consumed (its {@code group_lead} mapping exists) is
 * treated as caught up. A group with NO {@code group_lead} mapping is not yet known at all, so it
 * is NOT caught up — though in practice the close never reaches the gate for such a group (the
 * service layer resolves the lead first and defers with {@code UnknownGroupException}). A
 * deployment that tracks a finer membership watermark overrides {@link #isCaughtUp(UUID)}.
 */
@Component
public class GroupMembershipReadiness {

  /**
   * SQL flag table check: a group is explicitly held warming-up if a row exists in {@code
   * group_membership_pending} for it. The membership consumer would insert such a marker when it
   * observes (out of band) that it is behind; the default deployment never writes the table, so
   * this is normally empty and the group is caught up. Kept as a simple, non-RLS reference signal
   * (like {@code group_lead}) so the gate has a deterministic, overridable hook without a schema
   * change to the close tables.
   */
  private static final String PENDING_SQL =
      "SELECT count(*) FROM group_membership_pending WHERE group_id = ?";

  private final GroupLeadResolver leadResolver;
  private final JdbcTemplate jdbcTemplate;

  public GroupMembershipReadiness(GroupLeadResolver leadResolver, JdbcTemplate jdbcTemplate) {
    this.leadResolver = leadResolver;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Whether the {@code group_member} projection is caught up for the group, so the close may trust
   * the resolved member set. The default: the group's {@code GroupDefined} has been consumed AND no
   * explicit pending-membership marker is recorded for it.
   *
   * @param groupId the consolidation group
   * @return {@code true} if the projection is caught up; {@code false} to HOLD the close warming-up
   */
  public boolean isCaughtUp(UUID groupId) {
    if (leadResolver.leadOf(groupId).isEmpty()) {
      // The group's GroupDefined has not been consumed at all — definitely not caught up. (The
      // service layer normally defers before reaching here; this is belt-and-braces.)
      return false;
    }
    Integer pending = jdbcTemplate.queryForObject(PENDING_SQL, Integer.class, groupId);
    return pending == null || pending == 0;
  }
}
