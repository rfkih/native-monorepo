package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.service.GroupService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d #38 — the {@code group_membership} STRUCTURAL DB invariants, proven against a RAW connection
 * (not the Hibernate path the {@code GroupAcceptanceTest} exercises). Two DB-level guards must fail
 * a direct INSERT even when the application layer is bypassed entirely:
 *
 * <ul>
 *   <li>{@code ck_group_membership_lead_is_tenant} — a row whose {@code company_id} differs from
 *       its {@code lead_company_id} is rejected, so a raw writer cannot mis-stamp a membership to a
 *       tenant other than the owning group's lead (the tenancy guard is a DB invariant, not only
 *       the application's bound-tenant stamping + the RLS WITH CHECK);
 *   <li>{@code uq_group_membership_active} — a second OPEN (active) row for the same {@code (group,
 *       member)} pair is rejected, so "at most one active membership" holds even against a direct
 *       insert that skips the read-then-insert convention.
 * </ul>
 *
 * <p>The raw inserts run as the unprivileged {@code app_user} (the same role Flyway/Hibernate use),
 * with the session tenant GUC bound to the lead so the RLS {@code WITH CHECK} itself passes — the
 * point is that the CHECK constraint / unique index fire <em>independently</em> of RLS. The group +
 * an initial active membership are seeded through the REAL {@link GroupService} so the row shapes
 * match production.
 */
@SpringBootTest
class GroupMembershipDbInvariantTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private GroupService groupService;

  @Test
  void aRawInsertWithCompanyIdNotEqualToLeadViolatesTheLeadIsTenantCheck() throws Exception {
    UUID lead = bootstrapCompany("Lead Co", "IDR");
    UUID member = UUID.randomUUID();
    UUID groupId = createGroupLedBy(lead, "DB Invariant Group");
    UUID otherTenant = UUID.randomUUID();

    // A direct INSERT that stamps company_id to a DIFFERENT tenant than lead_company_id. We bind
    // the
    // session tenant to that mis-stamped company_id so RLS's own WITH CHECK is satisfied — proving
    // the ck_group_membership_lead_is_tenant CHECK (company_id = lead_company_id::text) is what
    // rejects it, not RLS.
    assertThatThrownBy(
            () ->
                rawInsertMembership(
                    otherTenant.toString(),
                    UUID.randomUUID(),
                    groupId,
                    member,
                    /* leadCompanyId= */ lead,
                    /* companyId= */ otherTenant.toString(),
                    "9999-12-31"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_group_membership_lead_is_tenant");
  }

  @Test
  void aRawSecondActiveRowForTheSamePairTripsThePartialUniqueIndex() throws Exception {
    UUID lead = bootstrapCompany("Lead Co", "IDR");
    UUID member = UUID.randomUUID();
    UUID groupId = createGroupLedBy(lead, "Uniqueness Group");

    // Seed the FIRST active membership through the real service (a well-formed open-ended row).
    TenantContext.callAs(
        lead.toString(),
        "owner",
        () -> groupService.addMember(new AddMemberCommand(groupId, member)));

    // A direct INSERT of a SECOND open-ended (active) row for the SAME (group, member), correctly
    // lead-stamped (so the lead-is-tenant CHECK passes and we isolate the uniqueness invariant),
    // with
    // the session tenant bound to the lead so RLS passes too. The partial unique index
    // uq_group_membership_active must reject it.
    assertThatThrownBy(
            () ->
                rawInsertMembership(
                    lead.toString(),
                    UUID.randomUUID(),
                    groupId,
                    member,
                    /* leadCompanyId= */ lead,
                    /* companyId= */ lead.toString(),
                    "9999-12-31"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uq_group_membership_active");

    // A CLOSED (stamped effective_to) second row is fine — only OPEN windows are constrained.
    // Proves
    // the index is PARTIAL over active rows, not the whole history.
    int closed =
        rawInsertMembership(
            lead.toString(),
            UUID.randomUUID(),
            groupId,
            member,
            lead,
            lead.toString(),
            "2026-06-30");
    assertThat(closed).isEqualTo(1);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private UUID bootstrapCompany(String name, String baseCurrency) {
    return companyService
        .createCompany(new CreateCompanyCommand(name, baseCurrency, "id", "HQ", "outlet", "owner"))
        .company()
        .getId();
  }

  private UUID createGroupLedBy(UUID lead, String name) throws Exception {
    return TenantContext.callAs(
        lead.toString(),
        "owner",
        () -> groupService.createGroup(new CreateGroupCommand(name, "IDR")).getId());
  }

  /**
   * Inserts ONE {@code group_membership} row over a fresh {@code app_user} connection with {@code
   * sessionTenant} bound as {@code app.current_tenant} (so the RLS USING/WITH CHECK is satisfied),
   * bypassing Hibernate entirely. Returns the affected row count; throws {@link SQLException} if a
   * CHECK / unique invariant rejects it.
   */
  private int rawInsertMembership(
      String sessionTenant,
      UUID id,
      UUID groupId,
      UUID member,
      UUID leadCompanyId,
      String companyId,
      String effectiveTo)
      throws SQLException {
    try (Connection conn =
            java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
        Statement set = conn.createStatement()) {
      set.execute("SET app.current_tenant = '" + sessionTenant + "'");
      try (var ps =
          conn.prepareStatement(
              "INSERT INTO group_membership (id, group_id, member_company_id, lead_company_id,"
                  + " effective_from, effective_to, created_at, created_by, updated_at, updated_by,"
                  + " version, company_id) VALUES (?, ?, ?, ?, DATE '2026-06-01', DATE '"
                  + effectiveTo
                  + "', now(), 'raw-test', now(), 'raw-test', 0, ?)")) {
        ps.setObject(1, id);
        ps.setObject(2, groupId);
        ps.setObject(3, member);
        ps.setObject(4, leadCompanyId);
        ps.setString(5, companyId);
        return ps.executeUpdate();
      }
    }
  }
}
