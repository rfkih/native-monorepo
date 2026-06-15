package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.messaging.ConsolidationClosedSchema;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 4a — the GROUP-close {@code ConsolidationClosed} producer (the loop closer). Proves a
 * CLOSED group close emits {@code ConsolidationClosed(company_id = lead, group_id = G)} into the
 * outbox atomically with the summary; a HELD close (e.g. PENDING_MEMBERS) emits NOTHING; a
 * re-delivery at the SAME close_run_seq does not double-emit; and a re-close at a HIGHER seq emits
 * a FRESH event.
 */
@SpringBootTest
class GroupCloseConsolidationClosedTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupCloseService closeService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUp() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aClosedGroupCloseEmitsConsolidationClosedWithTheGroupIdAtomically() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(group, memberB, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(group, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));
    fx.ingestTrialBalance(group, memberB, PERIOD, "IDR", List.of(expense(2_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(group, PERIOD, 1);
    assertThat(result.isClosed()).isTrue();

    List<GenericRecord> events = decodeConsolidationClosed();
    assertThat(events).hasSize(1);
    GenericRecord event = events.getFirst();
    // company_id = the group's LEAD; group_id = G (the group-close discriminator); period.
    assertThat(event.get("company_id").toString()).isEqualTo(lead.toString());
    assertThat(event.get("group_id").toString()).isEqualTo(group.toString());
    assertThat(event.get("period").toString()).isEqualTo(PERIOD);
    // The outbox row carries the lead as company_id (the tenant the notification belongs to).
    assertThat(outboxCompanyId()).isEqualTo(lead.toString());
  }

  @Test
  void aHeldGroupCloseEmitsNoConsolidationClosed() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(group, memberB, LocalDate.of(2026, 1, 1));
    // Only A reports; B (active at period-end) has no trial balance -> PENDING_MEMBERS (HELD).
    fx.ingestTrialBalance(group, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    GroupCloseResult result = closeService.close(group, PERIOD, 1);
    assertThat(result.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS);

    // A held close emits NOTHING — never present a mid-flight consolidation as final.
    assertThat(decodeConsolidationClosed()).isEmpty();
  }

  @Test
  void aReDeliveryAtTheSameSeqDoesNotDoubleEmit() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberA, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(group, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    closeService.close(group, PERIOD, 1);
    // Re-run at the SAME seq -> idempotent no-op (firstRun=false), and crucially NO second event.
    GroupCloseResult reRun = closeService.close(group, PERIOD, 1);
    assertThat(reRun.firstRun()).isFalse();

    assertThat(decodeConsolidationClosed()).hasSize(1);
  }

  @Test
  void aReCloseAtAHigherSeqEmitsAFreshConsolidationClosed() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberA, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(group, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    closeService.close(group, PERIOD, 1);
    // A corrected re-close at a HIGHER close_run_seq supersedes the prior and legitimately emits a
    // FRESH ConsolidationClosed (the period closed again at a new run).
    GroupCloseResult reClose = closeService.close(group, PERIOD, 2);
    assertThat(reClose.isClosed()).isTrue();

    List<GenericRecord> events = decodeConsolidationClosed();
    assertThat(events).hasSize(2);
    assertThat(events)
        .allSatisfy(e -> assertThat(e.get("group_id").toString()).isEqualTo(group.toString()));
  }

  // ----------------------------------------------------------------------- helpers

  private List<GenericRecord> decodeConsolidationClosed() {
    List<GenericRecord> out = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT payload FROM outbox WHERE event_type = 'ConsolidationClosed'"
                    + " ORDER BY occurred_at, id")) {
      while (rs.next()) {
        out.add(AvroSerde.deserialize(rs.getBytes("payload"), ConsolidationClosedSchema.schema()));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private String outboxCompanyId() {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT company_id FROM outbox WHERE event_type = 'ConsolidationClosed' LIMIT 1")) {
      rs.next();
      return rs.getString(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection adminConnection() throws java.sql.SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
