package id.co.nativeapp.finance.group;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The REVERSE of {@link GroupMemberRepository}: resolves which consolidation group(s) a MEMBER
 * company belongs to, from the local {@code member_group_index} reference table (P3d SEAM 4a).
 *
 * <p><strong>Why a separate non-RLS reference table.</strong> The LEAD-scoped {@code group_member}
 * read model is owned by each group's lead (rule 4, RLS-confined to the lead). A within-company
 * close runs as the CLOSING company — the MEMBER, never the lead — so it cannot read {@code
 * group_member} under RLS to discover its own group memberships (the rows belong to a different
 * tenant). The {@code member_group_index} table (member_company_id → group_id, with the membership
 * effective window) is therefore kept as cross-tenant reference data NOT under RLS — exactly like
 * finance's {@code group_lead} / {@code chart_of_account} reference tables — written alongside the
 * authoritative {@code group_member} row by the {@code GroupMembershipChanged} consumer. The close
 * reads it (as the member) to find the groups it is ACTIVE in at period-end, then emits one {@code
 * TrialBalancePublished} per such group. It holds only the public member→group structural mapping +
 * effective window (no amount, no PII).
 */
@Component
public class MemberGroupResolver {

  private static final String UPSERT_SQL =
      "INSERT INTO member_group_index (member_company_id, group_id, effective_from, effective_to) "
          + "VALUES (?, ?, ?, ?) "
          + "ON CONFLICT (member_company_id, group_id) DO UPDATE SET "
          + "effective_from = EXCLUDED.effective_from, effective_to = EXCLUDED.effective_to";

  // The groups a member is ACTIVE in at a given date (the half-open window the group_member read
  // model uses: effective_from <= asOf AND asOf < effective_to), so a membership that closed before
  // period-end is excluded and one that opens exactly at period-end is included — deterministic and
  // identical to GroupMemberRepository.findActiveAt.
  private static final String ACTIVE_GROUPS_SQL =
      "SELECT group_id FROM member_group_index "
          + "WHERE member_company_id = ? AND effective_from <= ? AND ? < effective_to";

  private final JdbcTemplate jdbcTemplate;

  public MemberGroupResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Records (idempotently) the member→group membership window — mirrors the post-change window of a
   * {@code GroupMembershipChanged} (ADDED opens/re-opens, REMOVED closes). Runs on the caller's
   * transactional connection so it commits atomically with the {@code group_member} write.
   */
  public void record(
      UUID memberCompanyId, UUID groupId, LocalDate effectiveFrom, LocalDate effectiveTo) {
    jdbcTemplate.update(
        UPSERT_SQL,
        memberCompanyId,
        groupId,
        java.sql.Date.valueOf(effectiveFrom),
        java.sql.Date.valueOf(effectiveTo));
  }

  /**
   * The groups the member is ACTIVE in at {@code asOf} (period-end). Empty when the company belongs
   * to no group (it still does a within-company close — it just publishes no trial balance).
   */
  public List<UUID> activeGroupsOf(UUID memberCompanyId, LocalDate asOf) {
    java.sql.Date as = java.sql.Date.valueOf(asOf);
    return jdbcTemplate.query(
        ACTIVE_GROUPS_SQL,
        (rs, rowNum) -> UUID.fromString(rs.getString("group_id")),
        memberCompanyId,
        as,
        as);
  }
}
