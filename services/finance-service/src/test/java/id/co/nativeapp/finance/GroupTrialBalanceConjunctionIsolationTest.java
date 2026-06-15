package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.group.messaging.GroupDefinedEvent;
import id.co.nativeapp.finance.grouptb.domain.GroupTrialBalanceLine;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import id.co.nativeapp.finance.grouptb.repository.GroupTrialBalanceLineRepository;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 2 — the TWO-GUC CONJUNCTION RLS proof for the finance {@code group_trial_balance} table,
 * against the real Flyway migration + the auto-applied two-GUC RLS aspect (rule 5), under the
 * unprivileged {@code app_user} (FORCE RLS binds even the owning role).
 *
 * <p>Proves the binding state machine fails closed every way:
 *
 * <ul>
 *   <li>(b) GROUP VIEW: bound (tenant = lead, group = G) reads the group's ingested trial balance,
 *       but the SAME bound scope sees NOTHING of a DIFFERENT member's company_id-scoped {@code
 *       ledger_posting} (the lead's group view never leaks another member's standalone ledger);
 *   <li>(a) a plain member viewer (tenant = member-B, NO group) reads NOTHING from {@code
 *       group_trial_balance} — app.current_group unset -> the group clause is false (the
 *       byte-identical single-tenant path);
 *   <li>(d) app.current_group set but app.current_tenant unset (and vice-versa) -> NOTHING (each
 *       partial binding fails closed).
 * </ul>
 */
@SpringBootTest
@org.springframework.context.annotation.Import(
    GroupTrialBalanceConjunctionIsolationTest.GroupTbReader.class)
class GroupTrialBalanceConjunctionIsolationTest extends PostgresRlsTestBase {

  private static final String ACTOR = "finance-consumer";

  @Autowired private GroupTrialBalanceIngestService ingestService;

  @Autowired
  private id.co.nativeapp.finance.group.service.GroupReadModelService groupReadModelService;

  @Autowired private GroupTbReader reader;

  @Test
  void groupViewerSeesTheGroupTrialBalanceButNotAnotherMembersLedger() throws Exception {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    // Register the group (lead = the bound tenant for every group write/read) so the ingest service
    // can resolve the lead from the group read model.
    groupReadModelService.handleGroupDefined(
        new GroupDefinedEvent(UUID.randomUUID(), groupId, lead, "IDR", "Group"));

    // Ingest member B's trial balance into the group (bound to tenant=lead, group=G internally).
    UUID tbEventId = UUID.randomUUID();
    ingestService.handle(
        new TrialBalancePublishedEvent(
            tbEventId,
            memberB,
            groupId,
            "2026-06",
            "IDR",
            true,
            false,
            List.of(
                new TrialBalanceLine(
                    "4000", "REVENUE", "REVENUE", 1_500_000L, "IDR", null, null))));

    // Seed member B's OWN standalone ledger_posting (company_id = B), over the admin connection.
    seedMemberBLedgerPostingAsAdmin(memberB);

    // (b) The group viewer bound (tenant = lead, group = G) reads the group's trial balance.
    List<UUID> visibleTbGroups =
        TenantContext.callAsGroup(
            lead.toString(), groupId.toString(), ACTOR, () -> reader.visibleGroupTbGroupIds());
    assertThat(visibleTbGroups).containsExactly(groupId);

    // ...but the SAME group-bound scope sees NOTHING of member B's company_id-scoped
    // ledger_posting:
    // the ledger_posting policy keys on app.current_tenant = lead, and B's rows are company_id = B.
    long leakedLedger =
        TenantContext.callAsGroup(
            lead.toString(), groupId.toString(), ACTOR, () -> reader.ledgerPostingCount());
    assertThat(leakedLedger).isZero();

    // (a) A plain member-B viewer (tenant = B, NO group) reads NOTHING from group_trial_balance
    // (app.current_group never set -> group clause false), even though B is the member in the
    // lines.
    long bSeesGroupTb =
        TenantContext.callAs(memberB.toString(), ACTOR, () -> reader.groupTbCount());
    assertThat(bSeesGroupTb).isZero();

    // (d) Partial bindings fail closed each way, asserted directly on a raw app-role connection.
    assertThat(groupTbCountWithGucs(null, groupId.toString())).isZero(); // tenant unset
    assertThat(groupTbCountWithGucs(lead.toString(), null)).isZero(); // group unset
    assertThat(groupTbCountWithGucs(lead.toString(), groupId.toString())).isEqualTo(1L); // both set
  }

  @Test
  void anAttackerControlledMemberCompanyIdIsInertForRlsOnlyTheLeadIsTheTenant() throws Exception {
    // MEMBER-DIMENSION-INERT negative proof: the event's company_id (the MEMBER dimension) can
    // NEVER
    // become the RLS tenant. The owning tenant is the org-authoritative LEAD; the member id is just
    // a column. So even if the event carries a DIFFERENT real company as its company_id, the
    // written
    // row is owned by the lead and stays invisible to that member company.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID(); // company X — the org-authoritative lead of this group
    UUID otherMember = UUID.randomUUID(); // a DIFFERENT real company M, carried as event.company_id

    // Register the group led by X.
    groupReadModelService.handleGroupDefined(
        new GroupDefinedEvent(UUID.randomUUID(), groupId, lead, "IDR", "Group"));

    // Ingest a TrialBalancePublished whose MEMBER dimension (event.company_id) is M, not the lead.
    // The ingest resolves the lead (X) from the authoritative group_lead and binds (tenant=X, G);
    // M is attacker-controllable on the wire but is only ever the member_company_id column.
    UUID tbEventId = UUID.randomUUID();
    ingestService.handle(
        new TrialBalancePublishedEvent(
            tbEventId,
            otherMember,
            groupId,
            "2026-06",
            "IDR",
            true,
            false,
            List.of(
                new TrialBalanceLine("4000", "REVENUE", "REVENUE", 999_000L, "IDR", null, null))));

    // The row is OWNED by the lead X (company_id = X) and carries M only as the member DIMENSION.
    List<String[]> rows =
        TenantContext.callAsGroup(
            lead.toString(), groupId.toString(), ACTOR, () -> reader.visibleRowOwnerAndMember());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[0]).isEqualTo(lead.toString()); // company_id (owning tenant) == lead X
    assertThat(rows.get(0)[1]).isEqualTo(otherMember.toString()); // member_company_id == M (inert)

    // The member company M, as a PLAIN single-tenant viewer (NO group), sees NOTHING — its tenant
    // id
    // is not the row's company_id (that is the lead), and app.current_group is never set.
    long mSeesAsPlain =
        TenantContext.callAs(otherMember.toString(), ACTOR, () -> reader.groupTbCount());
    assertThat(mSeesAsPlain).isZero();

    // Even if M is smuggled in as BOTH the tenant AND the group is bound, the row's company_id is
    // X,
    // so the conjunction's company_id half excludes it: an attacker binding (tenant=M, group=G) on
    // a
    // raw app-role connection sees nothing. Only the real lead X with the group sees the row.
    assertThat(groupTbCountWithGucs(otherMember.toString(), groupId.toString())).isZero();
    assertThat(groupTbCountWithGucs(lead.toString(), groupId.toString())).isEqualTo(1L);
  }

  private void seedMemberBLedgerPostingAsAdmin(UUID memberB) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO ledger_posting (id, business_id, period, posting_type, amount_minor,"
                    + " currency, gl_account_code, source_event_id, posting_role,"
                    + " uses_illustrative_rules, unallocated, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id) VALUES (?, ?, '2026-06', 'REVENUE', 5000,"
                    + " 'IDR', '4000', ?, 'PRIMARY', false, false, now(), 'seed', now(), 'seed', 0,"
                    + " ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, UUID.randomUUID());
      ps.setObject(3, UUID.randomUUID());
      ps.setString(4, memberB.toString());
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Counts group_trial_balance rows over a raw app-role connection with the given GUCs set. */
  private long groupTbCountWithGucs(String tenant, String group) {
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        java.sql.Statement set = conn.createStatement()) {
      if (tenant != null) {
        set.execute("SET app.current_tenant = '" + tenant + "'");
      }
      if (group != null) {
        set.execute("SET app.current_group = '" + group + "'");
      }
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM group_trial_balance")) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the GUC(s). */
  @org.springframework.stereotype.Component
  public static class GroupTbReader {

    private final GroupTrialBalanceLineRepository tbRepository;
    private final id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository
        ledgerRepository;

    public GroupTbReader(
        GroupTrialBalanceLineRepository tbRepository,
        id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository ledgerRepository) {
      this.tbRepository = tbRepository;
      this.ledgerRepository = ledgerRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> visibleGroupTbGroupIds() {
      return tbRepository.findAll().stream()
          .map(GroupTrialBalanceLine::getGroupId)
          .distinct()
          .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long groupTbCount() {
      return tbRepository.count();
    }

    /**
     * Each visible row's {@code [company_id (owning tenant), member_company_id (dimension)]} — to
     * prove the owner is the lead and the member id is just a column, never the tenant.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String[]> visibleRowOwnerAndMember() {
      return tbRepository.findAll().stream()
          .map(row -> new String[] {row.getCompanyId(), row.getMemberCompanyId().toString()})
          .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long ledgerPostingCount() {
      return ledgerRepository.count();
    }
  }
}
