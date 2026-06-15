package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedSchema;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.withinclose.service.WithinCompanyCloseService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 4a — the END-TO-END loop: a member's WITHIN-COMPANY close emits a balanced {@code
 * TrialBalancePublished} (the SEAM-4a producer), which the SEAM-2 consumer ingests into {@code
 * group_trial_balance}, and a subsequent GROUP close consolidates it to CLOSED. This proves the
 * produced event is decode-compatible with the consumer (same {@code .avsc}) AND that the balanced
 * trial balance the producer publishes passes the SEAM-3b native-balance gate — the whole reason
 * the close adds the retained-earnings equity closing line.
 *
 * <p>Two members each do a within-company close; their published trial balances are fed through the
 * real SEAM-2 ingest (decoding the exact outbox bytes), then the group closes over them.
 */
@SpringBootTest
class WithinCloseToGroupCloseEndToEndTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";
  private static final Instant IN_PERIOD = Instant.parse("2026-06-15T00:00:00Z");

  @Autowired private RevenuePostingService revenueService;
  @Autowired private ExpensePostingService expenseService;
  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private WithinCompanyCloseService closeService;
  @Autowired private GroupCloseService groupCloseService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUp() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aMemberWithinCloseFeedsTheGroupCloseEndToEnd() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(group, memberB, LocalDate.of(2026, 1, 1));

    // Member A's ledger: revenue 10M, expense 4M (net profit 6M).
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            memberA.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            memberA.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "cogs",
            IN_PERIOD));
    // Member B's ledger: revenue 7M, expense 2M (net profit 5M).
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            memberB.toString(),
            outlet,
            Money.ofMinor(7_000_000L, "IDR"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            memberB.toString(),
            outlet,
            Money.ofMinor(2_000_000L, "IDR"),
            "cogs",
            IN_PERIOD));

    // Each member runs its WITHIN-COMPANY close (the producer).
    callAsCompany(memberA, () -> closeService.close(PERIOD, "IDR"));
    callAsCompany(memberB, () -> closeService.close(PERIOD, "IDR"));

    // The SEAM-2 ingest consumes the EXACT TrialBalancePublished bytes each close emitted.
    List<byte[]> published = outboxPayloads("TrialBalancePublished");
    assertThat(published).hasSize(2);
    for (byte[] payload : published) {
      TrialBalancePublishedEvent event =
          TrialBalancePublishedSchema.decode(UUID.randomUUID(), payload);
      // The published trial balance is reconciled (the balancing equity closing line guarantees
      // it).
      assertThat(event.reconciled()).isTrue();
      ingestService.handle(event);
    }

    // The GROUP close consolidates the ingested member trial balances. It passes the SEAM-3b
    // native-balance gate precisely because each member's published trial balance sums to zero.
    GroupCloseResult result = groupCloseService.close(group, PERIOD, 1);
    assertThat(result.isClosed()).isTrue();

    // Group P&L: revenue 10M+7M = 17M, expense 4M+2M = 6M, net 11M (no intercompany here).
    assertThat(summaryMinor(group, "group_revenue_minor")).isEqualTo(17_000_000L);
    assertThat(summaryMinor(group, "group_expense_minor")).isEqualTo(6_000_000L);
    assertThat(summaryMinor(group, "group_net_minor")).isEqualTo(11_000_000L);

    // And the loop is closed both ways: the within-company ConsolidationClosed(group_id=null) per
    // member PLUS the group ConsolidationClosed(group_id=G) are all in the outbox.
    assertThat(outboxPayloads("ConsolidationClosed")).hasSize(3); // 2 within-company + 1 group
  }

  /**
   * P3d SEAM 4a + 3b END-TO-END, MULTI-CURRENCY — the seam's CENTRAL claim. Two members in
   * DIFFERENT functional currencies (a USD member + an IDR member) each run a real WITHIN-COMPANY
   * close, producing a balanced trial balance WITH the synthesized retained-earnings EQUITY closing
   * line. Their {@code TrialBalancePublished} are ingested (the real SEAM-2 consumer decoding the
   * exact outbox bytes), then a GROUP close in a reporting currency runs the SEAM-3b NATIVE-BALANCE
   * GATE over them — the gate that is SKIPPED on the single-currency path the other e2e test uses,
   * so the equity-closing-line claim is otherwise unproven.
   *
   * <p>This proves the equity-closing-line trial balance clears the EXACT gate it was built for:
   * the close is multi-currency (the USD member's currency differs from the IDR reporting
   * currency), so {@code unbalancedNativeMembers} runs over BOTH members; each native book sums
   * signed-to-zero ONLY because of the equity closing line, so both clear the gate; the close
   * reaches CLOSED, the CTA captures the closing-vs-average FX effect, {@code
   * uses_simplified_translation_policy=true}, and a {@code ConsolidationClosed(group_id=G)} is
   * emitted.
   */
  @Test
  void twoMembersInDifferentCurrenciesClearTheNativeBalanceGateViaTheirEquityClosingLines() {
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    // Reporting currency IDR; member IDR translates by identity, member USD must translate (the
    // multi-currency path that engages the SEAM-3b native-balance gate over BOTH members).
    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(group, memberUsd, LocalDate.of(2026, 1, 1));

    // Member IDR (functional IDR): revenue 10M, expense 4M -> net profit 6M. Its within-close TB is
    // REVENUE 10M + EXPENSE 4M + EQUITY closing -6M, summing signed-to-zero in IDR.
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            memberIdr.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            memberIdr.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "cogs",
            IN_PERIOD));
    // Member USD (functional USD): revenue $500, expense $200 -> net profit $300. Its within-close
    // TB
    // is REVENUE $500 + EXPENSE $200 + EQUITY closing -$300, summing signed-to-zero in USD. The
    // EQUITY closing line is the ONLY thing that makes this book balance natively — without it the
    // raw P&L nets +$300 and would fail the gate.
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            memberUsd.toString(),
            outlet,
            Money.ofMinor(500_00L, "USD"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            memberUsd.toString(),
            outlet,
            Money.ofMinor(200_00L, "USD"),
            "cogs",
            IN_PERIOD));

    // Each member runs its WITHIN-COMPANY close (the producer). The base currency is DERIVED from
    // each member's ledger (immutable base-currency rule) — no currency is declared here.
    callAsCompany(memberIdr, () -> closeService.close(PERIOD, null));
    callAsCompany(memberUsd, () -> closeService.close(PERIOD, null));

    // Ingest the EXACT TrialBalancePublished bytes each close emitted through the real SEAM-2
    // ingest,
    // asserting each member's derived base currency rode onto the wire.
    List<byte[]> published = outboxPayloads("TrialBalancePublished");
    assertThat(published).hasSize(2);
    boolean sawIdr = false;
    boolean sawUsd = false;
    for (byte[] payload : published) {
      TrialBalancePublishedEvent event =
          TrialBalancePublishedSchema.decode(UUID.randomUUID(), payload);
      assertThat(event.reconciled()).isTrue();
      sawIdr = sawIdr || "IDR".equals(event.baseCurrency());
      sawUsd = sawUsd || "USD".equals(event.baseCurrency());
      ingestService.handle(event);
    }
    // The base currency was DERIVED per member from its own ledger, not declared in the request.
    assertThat(sawIdr).as("IDR member published its IDR-derived base currency").isTrue();
    assertThat(sawUsd).as("USD member published its USD-derived base currency").isTrue();

    // The GROUP close runs the SEAM-3b native-balance gate over BOTH members (multi-currency path).
    GroupCloseResult result = groupCloseService.close(group, PERIOD, 1);

    // BOTH members cleared the native-balance gate (their equity closing lines made each native
    // book
    // sum to zero), so the close reached CLOSED rather than HELD MEMBER_TRIAL_BALANCE_UNBALANCED.
    assertThat(result.isClosed()).isTrue();

    // Group P&L (translated): revenue 10M (IDR) + $500@AVG(15,900)=7,950,000 = 17,950,000;
    // expense 4M (IDR) + $200@AVG(15,900)=3,180,000 = 7,180,000; net 10,770,000.
    assertThat(summaryMinor(group, "group_revenue_minor")).isEqualTo(17_950_000L);
    assertThat(summaryMinor(group, "group_expense_minor")).isEqualTo(7_180_000L);
    assertThat(summaryMinor(group, "group_net_minor")).isEqualTo(10_770_000L);

    // The CTA captured the closing-vs-average FX effect on the (natively-balanced) USD book: P&L at
    // AVERAGE (15,900), the EQUITY closing line at CLOSING (16,000). signed translated USD residual
    // =
    //   EXPENSE +3,180,000 - [ EQUITY(-$300@16,000 = -4,800,000) + REVENUE 7,950,000 ] = +30,000.
    // The IDR member is identity-translated (signed 0). CTA posts -30,000 so the set reconciles.
    assertThat(summaryFlag(group, "uses_simplified_translation_policy")).isTrue();
    assertThat(ctaSum(group)).isEqualTo(-30_000L);
    assertThat(entryTypeCount(group, "CTA")).isEqualTo(1L);

    // The loop is closed both ways: 2 within-company ConsolidationClosed(group_id=null) + 1 group
    // ConsolidationClosed(group_id=G).
    assertThat(outboxPayloads("ConsolidationClosed")).hasSize(3);
  }

  // ----------------------------------------------------------------------- helpers

  private static <T> T callAsCompany(UUID company, java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(company.toString(), "tester", action);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private List<byte[]> outboxPayloads(String eventType) {
    List<byte[]> out = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT payload FROM outbox WHERE event_type = '"
                    + eventType
                    + "' ORDER BY occurred_at, id")) {
      while (rs.next()) {
        out.add(rs.getBytes("payload"));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private long summaryMinor(UUID group, String column) {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT "
                    + column
                    + " FROM consolidation_summary WHERE group_id = '"
                    + group
                    + "' AND state <> 'SUPERSEDED'")) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean summaryFlag(UUID group, String column) {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT "
                    + column
                    + " FROM consolidation_summary WHERE group_id = '"
                    + group
                    + "' AND state <> 'SUPERSEDED'")) {
      rs.next();
      return rs.getBoolean(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private long ctaSum(UUID group) {
    return longQuery(
        "SELECT coalesce(sum(amount_minor),0) FROM consolidation_ledger WHERE group_id = '"
            + group
            + "' AND entry_type = 'CTA' AND posting_role = 'PRIMARY'");
  }

  private long entryTypeCount(UUID group, String entryType) {
    return longQuery(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '"
            + group
            + "' AND entry_type = '"
            + entryType
            + "'");
  }

  private long longQuery(String sql) {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection adminConnection() throws java.sql.SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
