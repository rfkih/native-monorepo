package id.co.nativeapp.finance.group.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a consolidation group's LEAD company id from the local {@code group_lead} reference
 * table — the tenant a {@code GroupMembershipChanged} write must be bound to (P3d SEAM 1, rule 2:
 * from a cached read model, never a synchronous call to org-service).
 *
 * <p><strong>Why a separate non-RLS reference table.</strong> The {@code GroupDefined} event
 * carries {@code lead_company_id} in its payload, so its read-model write can be tenant-bound
 * directly. But {@code GroupMembershipChanged} carries only {@code group_id} (the lead is NOT on
 * the wire), and the lead is exactly the tenant the {@link GroupMember} write needs — a
 * chicken-and-egg under RLS (you cannot read the RLS-scoped {@code group_ref} until you already
 * know its tenant). The {@code group_lead} table (group_id -&gt; lead_company_id) is therefore kept
 * as cross-tenant reference data NOT under RLS — exactly like finance's {@code chart_of_account} /
 * {@code mapping_rule} reference tables and the {@code processed_event} dedupe store — written
 * alongside {@code group_ref} on {@code GroupDefined}. The membership consumer reads it FIRST to
 * discover the lead, then binds that tenant and writes the RLS-scoped {@code group_member} row. It
 * holds no business amount and no PII — only the public group→lead structural mapping.
 *
 * <p>A query before the group's {@code GroupDefined} has been consumed returns empty; the
 * membership consumer then defers (throws so the record is retried/re-delivered) rather than
 * projecting under an unknown tenant — events for a group are partitioned on {@code group_id}, so
 * {@code GroupDefined} normally precedes any membership event for it.
 */
@Component
public class GroupLeadResolver {

  private static final String UPSERT_SQL =
      "INSERT INTO group_lead (group_id, lead_company_id) VALUES (?, ?) "
          + "ON CONFLICT (group_id) DO NOTHING";

  private static final String SELECT_SQL =
      "SELECT lead_company_id FROM group_lead WHERE group_id = ?";

  private final JdbcTemplate jdbcTemplate;

  public GroupLeadResolver(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Records the group→lead mapping (idempotent; a re-delivered {@code GroupDefined} is a no-op).
   * Runs on the caller's transactional connection so it commits atomically with the {@code
   * group_ref} upsert.
   */
  public void record(UUID groupId, UUID leadCompanyId) {
    jdbcTemplate.update(UPSERT_SQL, groupId, leadCompanyId);
  }

  /**
   * The lead company for a group, or empty if its {@code GroupDefined} has not been consumed yet.
   */
  public Optional<UUID> leadOf(UUID groupId) {
    try {
      UUID lead = jdbcTemplate.queryForObject(SELECT_SQL, UUID.class, groupId);
      return Optional.ofNullable(lead);
    } catch (EmptyResultDataAccessException none) {
      return Optional.empty();
    }
  }
}
