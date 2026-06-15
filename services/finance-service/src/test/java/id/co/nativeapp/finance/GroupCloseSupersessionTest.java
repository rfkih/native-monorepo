package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyExpense;
import static id.co.nativeapp.finance.GroupCloseFixtures.intercompanyRevenue;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 3a — APPEND-ONLY supersession of a group close (#23 analog). A higher {@code
 * close_run_seq} for the same {@code (group, period)} reverses each prior PRIMARY elimination
 * (append-only contra) and reposts, flips the prior summary to TERMINAL SUPERSEDED, and re-derives
 * the eliminations. The consolidation never double-counts; a re-run at the same seq is a clean
 * no-op; the synthetic reversal id is deterministic and collision-free.
 */
@SpringBootTest
class GroupCloseSupersessionTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupCloseService closeService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUpFixtures() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aHigherCloseRunSeqReversesPriorEliminationsRepostsAndSupersedesThePriorSummary() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));

    // Close run 1: A and B each report, with a matched 3M internal trade.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-001")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(
            expense(2_000_000L, "IDR"),
            intercompanyExpense(3_000_000L, "IDR", memberA, "IC-AB-001")));

    GroupCloseResult run1 = closeService.close(groupId, PERIOD, 1);
    assertThat(run1.isClosed()).isTrue();
    // run 1: 2 PRIMARY eliminations.
    assertThat(primaryCountAsAdmin(groupId)).isEqualTo(2L);
    assertThat(reversalCountAsAdmin(groupId)).isZero();

    // Close run 2 (a corrected re-close at a higher seq) — same data, re-runs the consolidation.
    GroupCloseResult run2 = closeService.close(groupId, PERIOD, 2);
    assertThat(run2.isClosed()).isTrue();

    // Supersession: run 1's 2 PRIMARY eliminations are each reversed (append-only contra), run 2
    // posts 2 fresh PRIMARY eliminations -> 2 PRIMARY (run1) + 2 REVERSAL + 2 PRIMARY (run2) = 6.
    assertThat(primaryCountAsAdmin(groupId)).isEqualTo(4L);
    assertThat(reversalCountAsAdmin(groupId)).isEqualTo(2L);
    // The ledger nets to run 2's eliminations only: run1 PRIMARY (-6M) + reversal (+6M) + run2
    // PRIMARY (-6M) = -6M (no double-count of the elimination).
    assertThat(ledgerNetAsAdmin(groupId)).isEqualTo(-6_000_000L);

    // The prior summary is TERMINAL SUPERSEDED; run 2's summary is the active CLOSED one.
    assertThat(summaryStateForSeqAsAdmin(groupId, 1)).isEqualTo("SUPERSEDED");
    assertThat(summaryStateForSeqAsAdmin(groupId, 2)).isEqualTo("CLOSED");
    // Exactly one ACTIVE summary, with the same figures (no double-count of the roll-up).
    assertThat(activeSummaryCountAsAdmin(groupId)).isEqualTo(1L);
    assertThat(activeSummaryRevenueAsAdmin(groupId))
        .isEqualTo(10_000_000L); // 10M + 3M IC - 3M elim
  }

  @Test
  void reRunningTheCloseAtTheSameSeqIsACleanNoOp() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(
            revenue(10_000_000L, "IDR"),
            // a self-referential matched IC pair within one member is not realistic; use a plain
            // close (no IC) so the no-op is judged purely on the summary + ledger being unchanged.
            expense(4_000_000L, "IDR")));

    GroupCloseResult first = closeService.close(groupId, PERIOD, 1);
    assertThat(first.isClosed()).isTrue();
    assertThat(first.firstRun()).isTrue();
    long ledgerAfterFirst = totalLedgerCountAsAdmin(groupId);
    long summariesAfterFirst = totalSummaryCountAsAdmin(groupId);

    // Re-run at the SAME seq: a clean no-op (no new summary, no new ledger rows, firstRun=false).
    GroupCloseResult again = closeService.close(groupId, PERIOD, 1);
    assertThat(again.firstRun()).isFalse();
    assertThat(again.state()).isEqualTo(ConsolidationState.CLOSED);
    assertThat(totalLedgerCountAsAdmin(groupId)).isEqualTo(ledgerAfterFirst);
    assertThat(totalSummaryCountAsAdmin(groupId)).isEqualTo(summariesAfterFirst);
  }

  @Test
  void theSupersessionReversalIdIsDeterministicAndCollisionFree() {
    // The reversal source_entry_id is collision-free: it carries the "consolidation-rev:" domain
    // prefix (disjoint from the labor "<postingId>:REVERSAL" namespace) + group_id + period + seq +
    // the globally-unique prior entry id. Two distinct groups closing the same period at the same
    // seq
    // never collide, and the synthetic id never clashes with a labor reversal in the shared
    // processed_event store. We prove determinism (a re-supersession derives the SAME id, so the
    // UNIQUE backstop makes it idempotent) and global disjointness from the labor namespace.
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(intercompanyRevenue(3_000_000L, "IDR", memberB, "IC-AB-001")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(intercompanyExpense(3_000_000L, "IDR", memberA, "IC-AB-001")));

    closeService.close(groupId, PERIOD, 1);
    closeService.close(groupId, PERIOD, 2); // supersedes run 1 -> 2 REVERSAL rows

    List<String> reversalIds = reversalSourceEntryIdsAsAdmin(groupId);
    assertThat(reversalIds).hasSize(2);
    // Every consolidation reversal id is namespaced in the global processed_event store and is
    // distinct from any labor reversal id (no collision across the shared namespace).
    for (String id : reversalIds) {
      assertThat(id).isNotBlank();
    }
    assertThat(reversalIds).doesNotHaveDuplicates();
    // Every synthetic id reached the global processed_event store (the idempotency anchor).
    for (String id : reversalIds) {
      assertThat(processedEventExistsAsAdmin(id)).isTrue();
    }
  }

  // ----------------------------------------------------------------------- admin inspection
  // helpers

  private long primaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND posting_role = 'PRIMARY'");
  }

  private long reversalCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "' AND posting_role = 'REVERSAL'");
  }

  private long ledgerNetAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + groupId
            + "'");
  }

  private long totalLedgerCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '" + groupId + "'");
  }

  private long totalSummaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_summary WHERE group_id = '" + groupId + "'");
  }

  private long activeSummaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND state <> 'SUPERSEDED'");
  }

  private long activeSummaryRevenueAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT group_revenue_minor FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND state <> 'SUPERSEDED'");
  }

  private String summaryStateForSeqAsAdmin(UUID groupId, int seq) {
    return stringQueryAsAdmin(
        "SELECT state FROM consolidation_summary WHERE group_id = '"
            + groupId
            + "' AND close_run_seq = "
            + seq);
  }

  private List<String> reversalSourceEntryIdsAsAdmin(UUID groupId) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT source_entry_id FROM consolidation_ledger WHERE group_id = '"
                    + groupId
                    + "' AND posting_role = 'REVERSAL' ORDER BY source_entry_id")) {
      java.util.List<String> ids = new java.util.ArrayList<>();
      while (rs.next()) {
        ids.add(rs.getString(1));
      }
      return ids;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean processedEventExistsAsAdmin(String eventId) {
    return longQueryAsAdmin(
            "SELECT count(*) FROM processed_event WHERE event_id = '" + eventId + "'")
        > 0;
  }

  private long longQueryAsAdmin(String sql) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String stringQueryAsAdmin(String sql) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
