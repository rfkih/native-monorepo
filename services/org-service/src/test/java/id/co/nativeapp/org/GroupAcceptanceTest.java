package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.dto.AddMemberCommand;
import id.co.nativeapp.org.group.dto.AddMemberResult;
import id.co.nativeapp.org.group.dto.CreateGroupCommand;
import id.co.nativeapp.org.group.dto.RemoveMemberCommand;
import id.co.nativeapp.org.group.messaging.GroupDefinedSchema;
import id.co.nativeapp.org.group.messaging.GroupMembershipChangedSchema;
import id.co.nativeapp.org.group.service.GroupService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 1 — the consolidation-group producer acceptance proof (org-service).
 *
 * <p>Proves end-to-end via the real {@link GroupService} under the unprivileged {@code app_user}
 * (so RLS engages): creating a group persists an immutable {@code reporting_currency} and emits
 * exactly one {@code GroupDefined}; adding a member emits one {@code
 * GroupMembershipChanged(ADDED)}; removing a member CLOSES the effective window (no hard-delete)
 * and emits {@code GroupMembershipChanged(REMOVED)}; a re-add / re-remove is an idempotent no-op
 * (no extra event).
 *
 * <p>The lead company is created via the real bootstrap flow; the group is created inside the
 * lead's tenant scope, so {@code lead_company_id == company_id == the lead}. Outbox rows are read
 * over the admin (BYPASSRLS) connection and decoded with {@code libs/events AvroSerde}.
 */
@SpringBootTest
class GroupAcceptanceTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private GroupService groupService;

  @Test
  void creatingAGroupPersistsImmutableReportingCurrencyAndEmitsGroupDefined() throws Exception {
    UUID lead = bootstrapCompany("Lead Co", "IDR");

    ConsolidationGroup group =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.createGroup(new CreateGroupCommand("My Group", "usd")));

    // Persisted: lead = the tenant, reporting currency normalized + immutable (CHAR(3) stored
    // "USD").
    assertThat(group.getLeadCompanyId()).isEqualTo(lead);
    assertThat(group.getReportingCurrency()).isEqualTo("USD");

    // Exactly one GroupDefined emitted, carrying the immutable reporting currency.
    List<GenericRecord> defined = outboxRecords("GroupDefined", GroupDefinedSchema.schema());
    assertThat(defined).hasSize(1);
    assertThat(defined.getFirst().get("group_id").toString()).isEqualTo(group.getId().toString());
    assertThat(defined.getFirst().get("lead_company_id").toString()).isEqualTo(lead.toString());
    assertThat(defined.getFirst().get("reporting_currency").toString()).isEqualTo("USD");

    // The reporting_currency column is updatable=false — the stored value never changes.
    assertThat(reportingCurrencyAsAdmin(group.getId())).isEqualTo("USD");
  }

  @Test
  void addingAndRemovingAMemberEmitsTheRightEventsAndClosesTheWindowOnRemove() throws Exception {
    UUID lead = bootstrapCompany("Lead Co", "IDR");
    UUID member = UUID.randomUUID();

    UUID groupId =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.createGroup(new CreateGroupCommand("Group", "IDR")).getId());

    // Add a member -> one GroupMembershipChanged(ADDED), open window, created = true.
    AddMemberResult addResult =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.addMember(new AddMemberCommand(groupId, member)));
    GroupMembership added = addResult.membership();
    assertThat(addResult.created()).isTrue();
    assertThat(added.isActive()).isTrue();
    assertThat(added.getEffectiveTo()).isEqualTo(GroupMembership.OPEN_ENDED);
    assertThat(added.getLeadCompanyId()).isEqualTo(lead);

    // Re-add the SAME member -> idempotent no-op: created = false, same row, still exactly ONE
    // ADDED event.
    AddMemberResult reAddResult =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.addMember(new AddMemberCommand(groupId, member)));
    assertThat(reAddResult.created()).isFalse();
    assertThat(reAddResult.membership().getId()).isEqualTo(added.getId());

    List<GenericRecord> afterAdd =
        outboxRecords("GroupMembershipChanged", GroupMembershipChangedSchema.schema());
    assertThat(afterAdd).hasSize(1);
    assertThat(afterAdd.getFirst().get("change_kind").toString()).isEqualTo("ADDED");
    assertThat(afterAdd.getFirst().get("member_company_id").toString())
        .isEqualTo(member.toString());

    // Remove the member -> the window is CLOSED (not hard-deleted) + one REMOVED event.
    GroupMembership removed =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.removeMember(new RemoveMemberCommand(groupId, member)));
    assertThat(removed.isActive()).isFalse();
    assertThat(removed.getEffectiveTo()).isNotEqualTo(GroupMembership.OPEN_ENDED);
    assertThat(removed.getEffectiveTo()).isBeforeOrEqualTo(LocalDate.now());

    // The row still EXISTS (closed, not deleted) — history preserved.
    assertThat(membershipRowCountAsAdmin(groupId, member)).isEqualTo(1L);

    // Re-remove -> idempotent no-op: there is no longer an ACTIVE membership to close, so the
    // writer returns null (and emits NO second REMOVED event).
    GroupMembership reRemove =
        TenantContext.callAs(
            lead.toString(),
            "owner",
            () -> groupService.removeMember(new RemoveMemberCommand(groupId, member)));
    assertThat(reRemove).isNull();

    List<GenericRecord> changes =
        outboxRecords("GroupMembershipChanged", GroupMembershipChangedSchema.schema());
    assertThat(changes).hasSize(2); // exactly ADDED + REMOVED, no idempotent duplicates
    assertThat(changes.get(1).get("change_kind").toString()).isEqualTo("REMOVED");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private UUID bootstrapCompany(String name, String baseCurrency) {
    return companyService
        .createCompany(new CreateCompanyCommand(name, baseCurrency, "id", "HQ", "outlet", "owner"))
        .company()
        .getId();
  }

  private List<GenericRecord> outboxRecords(String eventType, Schema schema) throws Exception {
    List<GenericRecord> out = new ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT payload FROM outbox WHERE event_type = '"
                    + eventType
                    + "' ORDER BY occurred_at, id")) {
      while (rs.next()) {
        out.add(AvroSerde.deserialize(rs.getBytes(1), schema));
      }
    }
    return out;
  }

  private String reportingCurrencyAsAdmin(UUID groupId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps =
            admin.prepareStatement(
                "SELECT reporting_currency FROM consolidation_group WHERE id = ?")) {
      ps.setObject(1, groupId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1).strip();
      }
    }
  }

  private long membershipRowCountAsAdmin(UUID groupId, UUID member) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps =
            admin.prepareStatement(
                "SELECT count(*) FROM group_membership WHERE group_id = ? AND member_company_id"
                    + " = ?")) {
      ps.setObject(1, groupId);
      ps.setObject(2, member);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
