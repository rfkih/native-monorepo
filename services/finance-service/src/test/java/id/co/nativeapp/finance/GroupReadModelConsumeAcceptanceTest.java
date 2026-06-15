package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.group.GroupMember;
import id.co.nativeapp.finance.group.GroupMemberRepository;
import id.co.nativeapp.finance.group.GroupRef;
import id.co.nativeapp.finance.group.GroupRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3d SEAM 1 — the finance CONSUMER acceptance + IDEMPOTENCY proof. finance consumes the
 * org-service {@code GroupDefined} + {@code GroupMembershipChanged} events (raw Avro bytes off a
 * real Kafka broker) into its local group read model ({@code group_ref} + {@code group_member}),
 * and a re-delivery of either event is a clean no-op (dedupe by event UUID via {@code
 * ProcessedEventStore}).
 *
 * <p>The reads run inside the group's LEAD tenant scope, so RLS permits them under the unprivileged
 * {@code app_user}; a re-delivered event never creates a duplicate row or re-opens a closed window.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(
    GroupReadModelConsumeAcceptanceTest.GroupReader.class)
class GroupReadModelConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String ACTOR = "finance-consumer";

  @Autowired private GroupReader groupReader;

  @Test
  void consumesBothEventsIdempotentlyIntoTheReadModel() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    String leadTenant = lead.toString();

    // 1) GroupDefined (delivered TWICE with the same event id — at-least-once redelivery).
    UUID definedEventId = UUID.randomUUID();
    var defined = GroupEventFixtures.groupDefined(groupId, lead, "USD", "Group");
    GroupEventFixtures.publishGroupDefined(
        KAFKA.getBootstrapServers(), groupId, definedEventId, defined);
    GroupEventFixtures.publishGroupDefined(
        KAFKA.getBootstrapServers(), groupId, definedEventId, defined);

    // group_ref lands once, with the immutable reporting currency.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<GroupRef> ref =
                  TenantContext.callAs(leadTenant, ACTOR, () -> groupReader.findRef(groupId));
              assertThat(ref).isPresent();
              assertThat(ref.get().getReportingCurrency()).isEqualTo("USD");
              assertThat(ref.get().getLeadCompanyId()).isEqualTo(lead);
            });

    // 2) GroupMembershipChanged(ADDED) — delivered TWICE with the same event id.
    UUID addedEventId = UUID.randomUUID();
    var added =
        GroupEventFixtures.membershipChanged(
            groupId, member, "ADDED", LocalDate.of(2026, 6, 15), GroupEventFixtures.OPEN_ENDED);
    GroupEventFixtures.publishMembershipChanged(
        KAFKA.getBootstrapServers(), groupId, addedEventId, added);
    GroupEventFixtures.publishMembershipChanged(
        KAFKA.getBootstrapServers(), groupId, addedEventId, added);

    // The member is in the ACTIVE set (open window), exactly one row.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<GroupMember> m =
                  TenantContext.callAs(
                      leadTenant, ACTOR, () -> groupReader.findMember(groupId, member));
              assertThat(m).isPresent();
              assertThat(m.get().isActive()).isTrue();
            });
    assertThat(memberRowCountAsAdmin(groupId, member)).isEqualTo(1L);

    // 3) GroupMembershipChanged(REMOVED) closes the window; exactly one row remains (no
    // hard-delete).
    UUID removedEventId = UUID.randomUUID();
    LocalDate removalDate = LocalDate.of(2026, 6, 20);
    var removed =
        GroupEventFixtures.membershipChanged(
            groupId, member, "REMOVED", LocalDate.of(2026, 6, 15), removalDate);
    GroupEventFixtures.publishMembershipChanged(
        KAFKA.getBootstrapServers(), groupId, removedEventId, removed);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<GroupMember> m =
                  TenantContext.callAs(
                      leadTenant, ACTOR, () -> groupReader.findMember(groupId, member));
              assertThat(m).isPresent();
              assertThat(m.get().isActive()).isFalse();
              assertThat(m.get().getEffectiveTo()).isEqualTo(removalDate);
            });

    // Re-deliver REMOVED — idempotent no-op; still exactly one (closed) row.
    GroupEventFixtures.publishMembershipChanged(
        KAFKA.getBootstrapServers(), groupId, removedEventId, removed);

    // A distinct marker (a fresh group) drains the partition so the duplicate is necessarily
    // applied.
    UUID markerGroup = UUID.randomUUID();
    UUID markerEvent = UUID.randomUUID();
    GroupEventFixtures.publishGroupDefined(
        KAFKA.getBootstrapServers(),
        markerGroup,
        markerEvent,
        GroupEventFixtures.groupDefined(markerGroup, lead, "USD", "Marker"));
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            leadTenant, ACTOR, () -> groupReader.findRef(markerGroup)))
                    .isPresent());

    assertThat(memberRowCountAsAdmin(groupId, member)).isEqualTo(1L);
  }

  private long memberRowCountAsAdmin(UUID groupId, UUID member) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM group_member WHERE group_id = ? AND member_company_id = ?")) {
      ps.setObject(1, groupId);
      ps.setObject(2, member);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the tenant GUC. */
  @org.springframework.stereotype.Component
  public static class GroupReader {

    private final GroupRefRepository refRepository;
    private final GroupMemberRepository memberRepository;

    public GroupReader(GroupRefRepository refRepository, GroupMemberRepository memberRepository) {
      this.refRepository = refRepository;
      this.memberRepository = memberRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<GroupRef> findRef(UUID groupId) {
      return refRepository.findById(groupId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<GroupMember> findMember(UUID groupId, UUID member) {
      return memberRepository.findByGroupIdAndMemberCompanyId(groupId, member);
    }
  }
}
