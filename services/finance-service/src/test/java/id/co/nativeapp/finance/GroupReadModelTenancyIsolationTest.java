package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.group.domain.GroupMember;
import id.co.nativeapp.finance.group.domain.GroupRef;
import id.co.nativeapp.finance.group.messaging.GroupDefinedEvent;
import id.co.nativeapp.finance.group.messaging.GroupMembershipChangedEvent;
import id.co.nativeapp.finance.group.repository.GroupMemberRepository;
import id.co.nativeapp.finance.group.repository.GroupRefRepository;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 1 — CROSS-TENANT ISOLATION of finance's local GROUP read model ({@code group_ref} +
 * {@code group_member}), relying on AUTO-applied RLS (rule 5) — the finance-side mirror of
 * org-service {@code GroupCrossTenantIsolationTest}.
 *
 * <p>A group defined by lead tenant A, with a member projected under A, is invisible to a different
 * tenant B: B's RLS-scoped reads of {@code group_ref} and {@code group_member} return EMPTY, and B
 * sees zero rows of either table over its own connection. The projection binds the group's LEAD as
 * the tenant (from the {@code GroupDefined} payload / the resolved {@code group_lead}); the reads
 * carry NO {@code WHERE company_id} — only the auto-RLS aspect sets the tenant GUC. Runs as the
 * unprivileged {@code app_user}; {@code FORCE ROW LEVEL SECURITY} binds even that owning role.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(GroupReadModelTenancyIsolationTest.GroupReader.class)
class GroupReadModelTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "finance-consumer";
  private static final String ACTOR_B = "viewer-b@example.co.id";

  @Autowired private GroupReadModelService readModelService;
  @Autowired private GroupReader groupReader;

  @Test
  void aGroupReadModelProjectedUnderLeadAIsInvisibleToTenantB() throws Exception {
    UUID groupId = UUID.randomUUID();
    UUID leadA = UUID.fromString(TENANT_A);
    UUID member = UUID.randomUUID();

    // Lead A's group is projected into the read model (group_ref + an active group_member). The
    // service binds A as the tenant from the event payload / resolved group_lead.
    readModelService.handleGroupDefined(
        new GroupDefinedEvent(UUID.randomUUID(), groupId, leadA, "IDR", "A Group"));
    readModelService.handleMembershipChanged(
        new GroupMembershipChangedEvent(
            UUID.randomUUID(),
            groupId,
            member,
            "ADDED",
            LocalDate.of(2026, 6, 15),
            GroupMember.OPEN_ENDED));

    // A sees its own group + member.
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> groupReader.visibleGroupIds()))
        .containsExactly(groupId);
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> groupReader.visibleMemberIds()))
        .containsExactly(member);

    // Inside B's scope: B sees NONE of A's read model (RLS only — no WHERE company_id).
    assertThat(TenantContext.callAs(TENANT_B, ACTOR_B, () -> groupReader.visibleGroupIds()))
        .isEmpty();
    assertThat(TenantContext.callAs(TENANT_B, ACTOR_B, () -> groupReader.visibleMemberIds()))
        .isEmpty();

    // Over B's own connection both RLS-scoped tables report zero rows.
    assertThat(TenantContext.callAs(TENANT_B, ACTOR_B, () -> rowsVisibleToBoundTenant("group_ref")))
        .isZero();
    assertThat(
            TenantContext.callAs(TENANT_B, ACTOR_B, () -> rowsVisibleToBoundTenant("group_member")))
        .isZero();

    // A's read model is still intact (B's bound reads observed nothing, and wrote nothing).
    assertThat(TenantContext.callAs(TENANT_A, ACTOR_A, () -> groupReader.visibleGroupIds()))
        .containsExactly(groupId);
  }

  /** Counts rows of {@code table} over a fresh app-role connection with B's tenant GUC bound. */
  private long rowsVisibleToBoundTenant(String table) throws Exception {
    String tenant = TenantContext.require().companyId();
    try (java.sql.Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        java.sql.Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + tenant + "'");
      try (java.sql.ResultSet rs = set.executeQuery("SELECT count(*) FROM " + table)) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the tenant GUC; the reads
   * carry no {@code WHERE company_id}, so only RLS constrains the result.
   */
  @org.springframework.stereotype.Component
  public static class GroupReader {

    private final GroupRefRepository refRepository;
    private final GroupMemberRepository memberRepository;

    public GroupReader(GroupRefRepository refRepository, GroupMemberRepository memberRepository) {
      this.refRepository = refRepository;
      this.memberRepository = memberRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> visibleGroupIds() {
      return refRepository.findAll().stream().map(GroupRef::getGroupId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<UUID> visibleMemberIds() {
      return memberRepository.findAll().stream().map(GroupMember::getMemberCompanyId).toList();
    }
  }
}
