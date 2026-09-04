package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent.LiabilityBucket;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityService;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Track P Phase P8 (THR run type, ADR 0035) coexistence proof: finance needed NO code changes for
 * THR — {@code payroll_run_ledger}'s supersession queries were already scoped {@code (period,
 * run_type)} by ADR 0032 (Track P phase P4), ahead of any THR run existing. This test is that
 * PROOF, not a build: a THR run and a REGULAR run for the SAME period each post their OWN labor
 * cost bucket AND their OWN liability entry, and NEITHER reverses the other — {@code
 * LaborSupersessionTest}'s within-type supersession matrix is untouched by this addition.
 */
@SpringBootTest
class LaborRunTypeCoexistenceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-30T12:00:00Z");
  private static final String PERIOD = "2026-06";

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PayrollLiabilityService liabilityService;
  @Autowired private PnlReader pnlReader;

  @Test
  void aThrRunAndARegularRunForTheSamePeriodPostIndependentLaborCostWithoutSuperseding()
      throws Exception {
    UUID regularRun = UUID.randomUUID();
    UUID thrRun = UUID.randomUUID();
    long regularAmount = 20_000_000L;
    long thrAmount = 10_000_000L;

    // REGULAR run_seq=1 lands.
    assertThat(
            laborService.handle(
                laborEvent(
                    UUID.randomUUID(), regularRun, 1, "REGULAR", "5100-SALARY", regularAmount)))
        .isTrue();
    ConsolidatedPnl afterRegular =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(PERIOD))
            .orElseThrow();
    assertThat(afterRegular.expense()).isEqualTo(Money.ofMinor(regularAmount, "IDR"));

    // THR run_seq=1 (its OWN independent series) lands for the SAME period — must NOT reverse the
    // REGULAR run, and must NOT itself be reversed. The period nets to the SUM of both.
    assertThat(
            laborService.handle(
                laborEvent(UUID.randomUUID(), thrRun, 1, "THR", "5150-THR", thrAmount)))
        .isTrue();
    ConsolidatedPnl afterThr =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(PERIOD))
            .orElseThrow();
    assertThat(afterThr.expense()).isEqualTo(Money.ofMinor(regularAmount + thrAmount, "IDR"));

    // Neither run was ever reversed — a SUPERSEDED run's presence would mean one of the two was
    // treated as the "prior" run of the SAME type, which is impossible here (different run_type).
    assertThat(runStateAsAdmin(regularRun)).isEqualTo("PENDING");
    assertThat(runStateAsAdmin(thrRun)).isEqualTo("PENDING");
    assertThat(reversalCountAsAdmin()).isZero();
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L); // REGULAR PRIMARY + THR PRIMARY, no reversal
  }

  @Test
  void aThrRunAndARegularRunForTheSamePeriodPostIndependentLiabilityEntriesWithoutSuperseding()
      throws Exception {
    UUID regularRun = UUID.randomUUID();
    UUID thrRun = UUID.randomUUID();

    assertThat(
            liabilityService.handle(
                liabilityEvent(
                    UUID.randomUUID(),
                    regularRun,
                    1,
                    "REGULAR",
                    20_400_000L,
                    List.of(
                        new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(17_798_333L, "IDR")),
                        new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(2_101_667L, "IDR")),
                        new LiabilityBucket("BPJS_KES_PAYABLE", Money.ofMinor(500_000L, "IDR"))))))
        .as("REGULAR liability posts")
        .isTrue();

    // THR carries no BPJS — just NET_WAGES_PAYABLE + PPH21_PAYABLE.
    assertThat(
            liabilityService.handle(
                liabilityEvent(
                    UUID.randomUUID(),
                    thrRun,
                    1,
                    "THR",
                    10_000_000L,
                    List.of(
                        new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(8_156_440L, "IDR")),
                        new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(1_843_560L, "IDR"))))))
        .as("THR liability posts independently — not superseding the REGULAR run")
        .isTrue();

    // Both entries exist, both liability_state=POSTED, neither run's entry was reversed by the
    // other (a cross-type supersession would have flipped one to SUPERSEDED / reversed its entry).
    assertThat(liabilityStateAsAdmin(regularRun)).isEqualTo("POSTED");
    assertThat(liabilityStateAsAdmin(thrRun)).isEqualTo("POSTED");
    assertThat(liabilityEntryIdAsAdmin(regularRun)).isNotNull();
    assertThat(liabilityEntryIdAsAdmin(thrRun)).isNotNull();
    assertThat(liabilityEntryIdAsAdmin(regularRun)).isNotEqualTo(liabilityEntryIdAsAdmin(thrRun));
    assertThat(entryCountAsAdmin()).isEqualTo(2L); // REGULAR entry + THR entry, no reversal
  }

  // ----------------------------------------------------------------------- helpers

  private LaborCostAllocatedEvent laborEvent(
      UUID eventId, UUID runId, int runSeq, String runType, String glAccount, long amountMinor) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        runType,
        PERIOD,
        OUTLET,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        false,
        false,
        OCCURRED_AT);
  }

  private PayrollLiabilitiesPostedEvent liabilityEvent(
      UUID eventId,
      UUID runId,
      int runSeq,
      String runType,
      long employerCostMinor,
      List<LiabilityBucket> liabilities) {
    return new PayrollLiabilitiesPostedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        runType,
        PERIOD,
        "IDR",
        Money.ofMinor(employerCostMinor, "IDR"),
        liabilities,
        false,
        OCCURRED_AT);
  }

  // runStateAsAdmin(UUID) and ledgerCountAsAdmin() are inherited from PostgresRlsTestBase (#35) —
  // never redeclared here (a subclass may not narrow their access, and they are the base's own
  // exclusive hoisted helpers, unlike longQueryAsAdmin/stringQueryAsAdmin below).

  private String liabilityStateAsAdmin(UUID runId) throws Exception {
    return stringQueryAsAdmin(
        "SELECT liability_state FROM payroll_run_ledger WHERE payroll_run_id = '" + runId + "'");
  }

  private UUID liabilityEntryIdAsAdmin(UUID runId) throws Exception {
    String value =
        stringQueryAsAdmin(
            "SELECT liability_entry_id FROM payroll_run_ledger WHERE payroll_run_id = '"
                + runId
                + "'");
    return value == null ? null : UUID.fromString(value);
  }

  private long reversalCountAsAdmin() throws Exception {
    return longQueryAsAdmin("SELECT count(*) FROM ledger_posting WHERE posting_role = 'REVERSAL'");
  }

  private long entryCountAsAdmin() throws Exception {
    return longQueryAsAdmin("SELECT count(*) FROM journal_entry");
  }

  private String stringQueryAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private long longQueryAsAdmin(String sql) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
