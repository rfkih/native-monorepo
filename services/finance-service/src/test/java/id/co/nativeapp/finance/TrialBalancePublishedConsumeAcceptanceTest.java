package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 2 — the finance {@code TrialBalancePublished} CONSUMER acceptance + IDEMPOTENCY proof.
 * finance consumes the event (raw Avro bytes off a real Kafka broker) into {@code
 * group_trial_balance}, bound to the group's LEAD tenant + group scope (the two-GUC conjunction),
 * and a re-delivery of the SAME event (same event UUID) is a clean no-op (dedupe by the real,
 * globally-unique event id via {@code ProcessedEventStore} + the {@code (source_event_id,
 * gl_account_code, posting_type)} UNIQUE backstop).
 *
 * <p>The group must be registered first (its {@code GroupDefined} consumed) so the consumer can
 * resolve the lead; the test publishes that, then the trial balance twice with one event id, then a
 * distinct marker to drain the partition so the duplicate has necessarily been
 * processed-and-skipped.
 */
@SpringBootTest
class TrialBalancePublishedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  @Test
  void consumesAndIngestsTrialBalanceIdempotently() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    // 1) GroupDefined for the group (so the consumer can resolve the lead from the read model).
    UUID definedEventId = UUID.randomUUID();
    GroupEventFixtures.publishGroupDefined(
        KAFKA.getBootstrapServers(),
        groupId,
        definedEventId,
        GroupEventFixtures.groupDefined(groupId, lead, "IDR", "Group"));

    // 2) TrialBalancePublished delivered TWICE with the SAME event id (at-least-once redelivery).
    UUID tbEventId = UUID.randomUUID();
    var event =
        TrialBalanceEventFixtures.trialBalancePublished(
            memberB,
            groupId,
            "2026-06",
            "IDR",
            true,
            false,
            List.of(
                TrialBalanceEventFixtures.line("4000", "REVENUE", "REVENUE", 1_500_000L, "IDR"),
                TrialBalanceEventFixtures.line("5000", "EXPENSE", "EXPENSE", 400_000L, "IDR")));
    TrialBalanceEventFixtures.publish(KAFKA.getBootstrapServers(), groupId, tbEventId, event);
    TrialBalanceEventFixtures.publish(KAFKA.getBootstrapServers(), groupId, tbEventId, event);

    // The two lines land exactly once each (2 rows for this event), never doubled.
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> assertThat(tbLineCountForEventAsAdmin(tbEventId)).isEqualTo(2L));

    // 3) A distinct marker (a fresh member's TB) drains the partition so the duplicate is
    // necessarily
    // already processed-and-skipped, making the no-double-count assertion race-free.
    UUID markerEventId = UUID.randomUUID();
    UUID markerMember = UUID.randomUUID();
    var marker =
        TrialBalanceEventFixtures.trialBalancePublished(
            markerMember,
            groupId,
            "2026-06",
            "IDR",
            true,
            false,
            List.of(TrialBalanceEventFixtures.line("4000", "REVENUE", "REVENUE", 1L, "IDR")));
    TrialBalanceEventFixtures.publish(KAFKA.getBootstrapServers(), groupId, markerEventId, marker);
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> assertThat(tbLineCountForEventAsAdmin(markerEventId)).isEqualTo(1L));

    // The duplicate added nothing: still exactly 2 lines for the original event.
    assertThat(tbLineCountForEventAsAdmin(tbEventId)).isEqualTo(2L);

    // The ingested rows carry B as the member dimension and are owned by the LEAD (company_id =
    // lead) — proving the write was bound to (tenant = lead, group = G) and passed the conjunction
    // WITH CHECK (a bad binding would have been rejected, so no rows would exist).
    assertThat(groupTbMembersForEventAsAdmin(tbEventId)).allMatch(memberB::equals);
    assertThat(groupTbOwnersForEventAsAdmin(tbEventId)).allMatch(lead.toString()::equals);
  }

  private long tbLineCountForEventAsAdmin(UUID eventId) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM group_trial_balance WHERE source_event_id = ?")) {
      ps.setObject(1, eventId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<UUID> groupTbMembersForEventAsAdmin(UUID eventId) {
    return queryColumnForEventAsAdmin("member_company_id", eventId).stream()
        .map(UUID::fromString)
        .toList();
  }

  private List<String> groupTbOwnersForEventAsAdmin(UUID eventId) {
    return queryColumnForEventAsAdmin("company_id", eventId);
  }

  private List<String> queryColumnForEventAsAdmin(String column, UUID eventId) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT " + column + " FROM group_trial_balance WHERE source_event_id = ?")) {
      ps.setObject(1, eventId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        java.util.List<String> values = new java.util.ArrayList<>();
        while (rs.next()) {
          values.add(rs.getString(1).strip());
        }
        return values;
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
