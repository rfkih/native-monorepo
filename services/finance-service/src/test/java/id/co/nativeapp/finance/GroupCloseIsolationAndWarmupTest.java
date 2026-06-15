package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationSummary;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.repository.ConsolidationSummaryRepository;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 3a — the consolidation tables under the two-GUC CONJUNCTION RLS, and the WARMING-UP
 * gate.
 *
 * <ul>
 *   <li>A group's consolidation_summary written under (lead, group) is invisible to a different
 *       group's scope and to a plain single-tenant viewer (the conjunction fails closed each way);
 *   <li>a group whose group_member projection is marked PENDING is HELD warming-up rather than
 *       consolidating a stale member set.
 * </ul>
 */
@SpringBootTest
@org.springframework.context.annotation.Import(GroupCloseIsolationAndWarmupTest.SummaryReader.class)
class GroupCloseIsolationAndWarmupTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";
  private static final String ACTOR = "finance-consolidation";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupCloseService closeService;
  @Autowired private SummaryReader reader;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUpFixtures() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aGroupCloseIsVisibleOnlyInsideItsOwnGroupScopeAndFailsClosedEveryOtherWay()
      throws Exception {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID otherGroup = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, member, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, member, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(groupId, PERIOD, 1);
    assertThat(result.isClosed()).isTrue();

    // (1) The group viewer bound (tenant = lead, group = G) sees the summary.
    List<UUID> visible =
        TenantContext.callAsGroup(
            lead.toString(), groupId.toString(), ACTOR, () -> reader.visibleSummaryGroupIds());
    assertThat(visible).containsExactly(groupId);

    // (2) A DIFFERENT group's scope (same lead, different group GUC) sees NOTHING — the group half
    // of the conjunction excludes it.
    long crossGroup =
        TenantContext.callAsGroup(
            lead.toString(), otherGroup.toString(), ACTOR, () -> reader.summaryCount());
    assertThat(crossGroup).isZero();

    // (3) A plain single-tenant viewer (tenant = lead, NO group) sees NOTHING from
    // consolidation_summary — app.current_group is unset, so the group clause is false (fail
    // closed).
    long plain = TenantContext.callAs(lead.toString(), ACTOR, () -> reader.summaryCount());
    assertThat(plain).isZero();

    // (4) Partial bindings on a raw app-role connection fail closed each way; both set sees the
    // row.
    assertThat(summaryCountWithGucs(null, groupId.toString())).isZero(); // tenant unset
    assertThat(summaryCountWithGucs(lead.toString(), null)).isZero(); // group unset
    assertThat(summaryCountWithGucs(lead.toString(), groupId.toString())).isEqualTo(1L); // both set
  }

  @Test
  void aWarmingUpGroupMemberProjectionHoldsTheCloseRatherThanConsolidatingAStaleMemberSet() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, member, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, member, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    // Mark the group's membership projection as PENDING (a known-pending membership change not yet
    // consumed) — the warming-up guard must HOLD the close.
    markMembershipPendingAsAdmin(groupId);

    GroupCloseResult held = closeService.close(groupId, PERIOD, 1);
    assertThat(held.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS_WARMING_UP);

    // Clear the marker (the projection has caught up) and re-close at a HIGHER seq: now it closes.
    clearMembershipPendingAsAdmin(groupId);
    GroupCloseResult closed = closeService.close(groupId, PERIOD, 2);
    assertThat(closed.isClosed()).isTrue();
  }

  // ----------------------------------------------------------------------- helpers

  private long summaryCountWithGucs(String tenant, String group) {
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        java.sql.Statement set = conn.createStatement()) {
      if (tenant != null) {
        set.execute("SET app.current_tenant = '" + tenant + "'");
      }
      if (group != null) {
        set.execute("SET app.current_group = '" + group + "'");
      }
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM consolidation_summary")) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void markMembershipPendingAsAdmin(UUID groupId) {
    execAsAdmin(
        "INSERT INTO group_membership_pending (group_id) VALUES ('"
            + groupId
            + "') ON CONFLICT DO NOTHING");
  }

  private void clearMembershipPendingAsAdmin(UUID groupId) {
    execAsAdmin("DELETE FROM group_membership_pending WHERE group_id = '" + groupId + "'");
  }

  private void execAsAdmin(String sql) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement()) {
      st.execute(sql);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the GUC(s). */
  @org.springframework.stereotype.Component
  public static class SummaryReader {

    private final ConsolidationSummaryRepository summaryRepository;

    public SummaryReader(ConsolidationSummaryRepository summaryRepository) {
      this.summaryRepository = summaryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> visibleSummaryGroupIds() {
      return summaryRepository.findAll().stream()
          .map(ConsolidationSummary::getGroupId)
          .distinct()
          .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long summaryCount() {
      return summaryRepository.count();
    }
  }
}
