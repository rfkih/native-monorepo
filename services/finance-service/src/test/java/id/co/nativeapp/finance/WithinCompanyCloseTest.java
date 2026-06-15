package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.consolidation.ConsolidationClosedSchema;
import id.co.nativeapp.finance.expense.ExpensePostingService;
import id.co.nativeapp.finance.expense.ExpenseRecordedEvent;
import id.co.nativeapp.finance.group.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.TrialBalancePublishedSchema;
import id.co.nativeapp.finance.revenue.RevenuePostingService;
import id.co.nativeapp.finance.revenue.SaleRecordedEvent;
import id.co.nativeapp.finance.withinclose.WithinCompanyCloseResult;
import id.co.nativeapp.finance.withinclose.WithinCompanyCloseService;
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
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 4a — the WITHIN-COMPANY CLOSE producer. Drives the close service directly (the tenant is
 * the closing company, bound via {@link TenantContext#callAs}) against the real Flyway migration +
 * RLS, under the unprivileged {@code app_user}. Proves: a within-company close gathers a BALANCED
 * trial balance (its P&amp;L lines + the balancing retained-earnings EQUITY closing line, summing
 * SIGNED-to-zero in the functional currency), emits a ConsolidationClosed(group_id=null) via the
 * outbox, and emits a balanced TrialBalancePublished per group the company belongs to — atomic with
 * the close. Idempotent: a re-close re-emits nothing.
 */
@SpringBootTest
class WithinCompanyCloseTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";
  // 2026-06-15 — inside PERIOD, so the period derived from occurred_at is 2026-06.
  private static final Instant IN_PERIOD = Instant.parse("2026-06-15T00:00:00Z");

  @Autowired private RevenuePostingService revenueService;
  @Autowired private ExpensePostingService expenseService;
  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private WithinCompanyCloseService closeService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUp() {
    fx = new GroupCloseFixtures(groupReadModel, null);
  }

  @Test
  void aWithinCompanyCloseEmitsABalancedTrialBalanceAndAConsolidationClosedWithNullGroup() {
    UUID company = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    // The company is a member of `group` (led by `lead`). The membership consumer populates the
    // member->group reverse index the close reads.
    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, company, LocalDate.of(2026, 1, 1));

    // Seed the company's ledger for PERIOD: REVENUE 10M (-> 4000), EXPENSE 4M (cogs -> 5100).
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "cogs",
            IN_PERIOD));

    WithinCompanyCloseResult result =
        callAsCompany(company, () -> closeService.close(PERIOD, "IDR"));

    assertThat(result.firstClose()).isTrue();
    assertThat(result.reconciled()).isTrue();
    assertThat(result.trialBalancePublishedGroups()).containsExactly(group);

    // ConsolidationClosed(group_id = null) emitted exactly once.
    List<GenericRecord> closedEvents =
        decodeOutbox("ConsolidationClosed", ConsolidationClosedSchema.schema());
    assertThat(closedEvents).hasSize(1);
    GenericRecord closed = closedEvents.getFirst();
    assertThat(closed.get("company_id").toString()).isEqualTo(company.toString());
    assertThat(closed.get("group_id")).isNull();
    assertThat(closed.get("period").toString()).isEqualTo(PERIOD);

    // TrialBalancePublished emitted once, for `group`, and its lines sum SIGNED-to-zero.
    List<GenericRecord> tbEvents =
        decodeOutbox("TrialBalancePublished", TrialBalancePublishedSchema.schema());
    assertThat(tbEvents).hasSize(1);
    GenericRecord tb = tbEvents.getFirst();
    assertThat(tb.get("company_id").toString()).isEqualTo(company.toString());
    assertThat(tb.get("group_id").toString()).isEqualTo(group.toString());
    assertThat(tb.get("base_currency").toString()).isEqualTo("IDR");
    assertThat((Boolean) tb.get("reconciled")).isTrue();

    // The whole point: the published trial balance BALANCES in the functional currency. Revenue 10M
    // (credit), expense 4M (debit), and a closing EQUITY line of -6M (credit, the net profit moved
    // to
    // retained earnings) => signed total (ASSET+EXPENSE) - (LIABILITY+EQUITY+REVENUE) = 4M - 10M -
    // (-6M)... computed via the same sign convention as the group native-balance gate => ZERO.
    long signed = signedResidual(tb);
    assertThat(signed).isZero();

    // There IS an equity closing line equal to the net P&L magnitude (signedPnl = expense - revenue
    // =
    // 4M - 10M = -6M; magnitude stored as published).
    assertThat(equityClosingMinor(tb)).isEqualTo(-6_000_000L);
  }

  @Test
  void aReCloseIsAnIdempotentNoOpThatReEmitsNothing() {
    UUID company = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    fx.defineGroup(group, lead, "IDR");
    fx.addMember(group, company, LocalDate.of(2026, 1, 1));
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(8_000_000L, "IDR"),
            IN_PERIOD));

    WithinCompanyCloseResult first =
        callAsCompany(company, () -> closeService.close(PERIOD, "IDR"));
    assertThat(first.firstClose()).isTrue();

    WithinCompanyCloseResult second =
        callAsCompany(company, () -> closeService.close(PERIOD, "IDR"));
    assertThat(second.firstClose()).isFalse();
    assertThat(second.closeId()).isEqualTo(first.closeId());
    assertThat(second.trialBalancePublishedGroups()).isEmpty();

    // Still exactly ONE of each event in the outbox (the re-close emitted nothing).
    assertThat(outboxCount("ConsolidationClosed")).isEqualTo(1L);
    assertThat(outboxCount("TrialBalancePublished")).isEqualTo(1L);
  }

  @Test
  void aCompanyInNoGroupStillEmitsConsolidationClosedButNoTrialBalance() {
    UUID company = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(5_000_000L, "IDR"),
            IN_PERIOD));

    WithinCompanyCloseResult result =
        callAsCompany(company, () -> closeService.close(PERIOD, "IDR"));

    assertThat(result.firstClose()).isTrue();
    assertThat(result.trialBalancePublishedGroups()).isEmpty();
    assertThat(outboxCount("ConsolidationClosed")).isEqualTo(1L);
    assertThat(outboxCount("TrialBalancePublished")).isZero();
  }

  // ----------------------------------------------------------------------- helpers

  private static <T> T callAsCompany(UUID company, java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(company.toString(), "tester", action);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The signed double-entry residual of the trial-balance lines (DEBIT +, CREDIT -). */
  private static long signedResidual(GenericRecord tb) {
    long signed = 0;
    for (GenericRecord line : lines(tb)) {
      String accountType = line.get("account_type").toString();
      long amount = (Long) line.get("amount_minor");
      signed +=
          switch (accountType) {
            case "ASSET", "EXPENSE" -> amount;
            default -> -amount; // LIABILITY, EQUITY, REVENUE
          };
    }
    return signed;
  }

  private static long equityClosingMinor(GenericRecord tb) {
    for (GenericRecord line : lines(tb)) {
      if ("EQUITY".equals(line.get("account_type").toString())) {
        return (Long) line.get("amount_minor");
      }
    }
    throw new AssertionError("No EQUITY closing line in the trial balance");
  }

  @SuppressWarnings("unchecked")
  private static List<GenericRecord> lines(GenericRecord tb) {
    return (List<GenericRecord>) tb.get("lines");
  }

  private List<GenericRecord> decodeOutbox(String eventType, org.apache.avro.Schema schema) {
    List<GenericRecord> out = new ArrayList<>();
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT payload FROM outbox WHERE event_type = '"
                    + eventType
                    + "' ORDER BY occurred_at, id")) {
      while (rs.next()) {
        out.add(AvroSerde.deserialize(rs.getBytes("payload"), schema));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private long outboxCount(String eventType) {
    try (Connection admin = adminConnection();
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT count(*) FROM outbox WHERE event_type = '" + eventType + "'")) {
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
