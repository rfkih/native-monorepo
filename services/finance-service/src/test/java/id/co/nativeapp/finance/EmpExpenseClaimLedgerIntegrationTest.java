package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedEvent;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledEvent;
import id.co.nativeapp.finance.empexpense.service.ExpenseClaimPostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests (real PostgreSQL, unprivileged {@code app_user}) for the {@code
 * employee_expense_claim_ledger} settle-once guard + reconciliation semantics (ADR 0030 §7, review
 * W1/S3/W3) — mirrors {@link ApWriterIntegrationTest} (real-DB writer proofs) and {@code
 * ExpenseClaimApproveConcurrencyTest} (employee-service, the {@link CyclicBarrier} genuine-race
 * idiom).
 *
 * <p><strong>Row-state assertions read via the ADMIN (BYPASSRLS) connection</strong> — the {@code
 * PostgresRlsTestBase} idiom every other test in this suite uses ({@code ledgerCountAsAdmin()} and
 * friends) — NOT via a raw {@code TenantContext.callAs(() -> claimLedgerRepository.find...)}. A
 * Spring Data repository proxy is built by {@code RepositoryFactoryBeanSupport} outside the normal
 * {@code AnnotationAwareAspectJAutoProxyCreator} pipeline, so the {@code libs/tenant} {@code
 * RlsAutoApplyAspect} never advises it when it is the FIRST/only transactional hop in the call —
 * the tenant GUC is never set and RLS fails closed (empty), exactly the documented "raw
 * TransactionTemplate reads run unbound" gotcha, just via a different unadvised entry point. Every
 * write in this test file instead goes through the real {@code @Component} writer beans (which ARE
 * advised, and which is how {@link
 * #concurrentSettlementsForAnUnrecognizedClaimConvergeToOneRowAndOneJournalEntry()} already proves
 * reads work correctly from a NESTED call inside an already-transactional method).
 */
@SpringBootTest
class EmpExpenseClaimLedgerIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "finance-consumer";
  private static final String ACTOR_B = "finance-consumer";

  @Autowired private ExpenseClaimPostingService postingService;

  // --------------------------------------------------------------------------------------------
  // (a) settle-once across two DISTINCT event ids, sequentially — one journal entry, second no-op.
  // --------------------------------------------------------------------------------------------

  @Test
  void settleOnceAcrossTwoDistinctEventIdsProducesExactlyOneSettlementJournalEntry()
      throws Exception {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    Instant approvedAt = Instant.parse("2026-08-01T09:00:00Z");
    Instant settledAt = Instant.parse("2026-08-03T09:00:00Z");

    approve(claimId, orgUnitId, employeeId, approvedAt);

    UUID settleEvent1 = UUID.randomUUID();
    UUID settleEvent2 = UUID.randomUUID();
    boolean first = settle(settleEvent1, claimId, orgUnitId, employeeId, settledAt);
    boolean second = settle(settleEvent2, claimId, orgUnitId, employeeId, settledAt);

    assertThat(first).isTrue();
    assertThat(second).isTrue(); // the handler ran, but the settle-once guard made it a no-op

    assertThat(journalEntryCountForSourceEventAsAdmin(settleEvent1))
        .as("the FIRST settlement posted exactly one journal entry")
        .isEqualTo(1L);
    assertThat(journalEntryCountForSourceEventAsAdmin(settleEvent2))
        .as("the SECOND settlement (settle-once no-op) posted NO journal entry")
        .isEqualTo(0L);

    assertThat(claimLedgerRowCountAsAdmin(claimId)).isEqualTo(1L);
    assertThat(isSettledAsAdmin(claimId)).isTrue();
  }

  // --------------------------------------------------------------------------------------------
  // (b) genuine concurrent race — two threads settling the SAME never-approved claim under
  //     DISTINCT event ids; the DataIntegrityViolationException path converges via
  //     ExpenseSettlementWriter#isSettledForReplay.
  // --------------------------------------------------------------------------------------------

  @Test
  void concurrentSettlementsForAnUnrecognizedClaimConvergeToOneRowAndOneJournalEntry()
      throws Exception {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    Instant settledAt = Instant.parse("2026-08-03T09:00:00Z");
    UUID eventId1 = UUID.randomUUID();
    UUID eventId2 = UUID.randomUUID();

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Boolean> attempt1 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return postingService.handleSettled(
                      settledEvent(eventId1, claimId, orgUnitId, employeeId, settledAt));
                });
    Callable<Boolean> attempt2 =
        () ->
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> {
                  barrier.await();
                  return postingService.handleSettled(
                      settledEvent(eventId2, claimId, orgUnitId, employeeId, settledAt));
                });

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> f1 = pool.submit(attempt1);
      Future<Boolean> f2 = pool.submit(attempt2);

      // Neither call throws (no unhandled 500) — both resolve, one via the fast pre-check path,
      // the other via the UNIQUE-race DataIntegrityViolationException recovery.
      assertThat(f1.get()).isTrue();
      assertThat(f2.get()).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(claimLedgerRowCountAsAdmin(claimId))
        .as("exactly ONE claim-ledger row ever exists for the claim")
        .isEqualTo(1L);
    assertThat(
            journalEntryCountForSourceEventAsAdmin(eventId1)
                + journalEntryCountForSourceEventAsAdmin(eventId2))
        .as("exactly ONE settlement journal entry — the race loser's transaction rolled back whole")
        .isEqualTo(1L);
  }

  // --------------------------------------------------------------------------------------------
  // (c) RLS isolation on employee_expense_claim_ledger: tenant B's approval of the SAME claim id
  //     (an adversarial UUID collision) can NEVER see or reconcile onto tenant A's row — it
  //     INSERTS its own, proving tenant A's row was invisible (a merge/leak would instead have
  //     taken the reconcile-UPDATE branch and there would be only ONE row).
  // --------------------------------------------------------------------------------------------

  @Test
  void tenantBCanNeverSeeOrMergeIntoTenantAsClaimLedgerRow() throws Exception {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitIdA = UUID.randomUUID();
    UUID employeeIdA = UUID.randomUUID();
    UUID orgUnitIdB = UUID.randomUUID();
    UUID employeeIdB = UUID.randomUUID();
    Instant approvedAt = Instant.parse("2026-08-01T09:00:00Z");

    approve(claimId, orgUnitIdA, employeeIdA, approvedAt);

    boolean approvedB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR_B,
            () ->
                postingService.handleApproved(
                    new ExpenseClaimApprovedEvent(
                        UUID.randomUUID(),
                        claimId,
                        TENANT_B,
                        orgUnitIdB,
                        employeeIdB,
                        Money.ofMinor(999_000L, "IDR"),
                        "supplies",
                        LocalDate.of(2026, 7, 20),
                        approvedAt)));
    assertThat(approvedB).isTrue();

    // TWO distinct rows exist for this claim_id — one per tenant. If RLS had leaked tenant A's row
    // to tenant B, tenant B's writer would have taken the reconcile-UPDATE branch and there would
    // be only ONE row (with tenant A's amount overwritten or untouched, either way merged).
    assertThat(claimLedgerRowCountAsAdmin(claimId))
        .as("tenant A's and tenant B's rows are independent (RLS-isolated), never merged")
        .isEqualTo(2L);
    assertThat(amountMinorForTenantAsAdmin(claimId, TENANT_A)).isEqualTo(250_000L);
    assertThat(amountMinorForTenantAsAdmin(claimId, TENANT_B)).isEqualTo(999_000L);
  }

  // --------------------------------------------------------------------------------------------
  // (d) out-of-order settlement-then-approval — WARN self-heal, then reconciliation, 2600 nets to
  //     zero across the two entries.
  // --------------------------------------------------------------------------------------------

  @Test
  void outOfOrderSettlementThenApprovalReconcilesAndNets2600ToZero() throws Exception {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    Instant approvedAt = Instant.parse("2026-08-01T09:00:00Z");
    Instant settledAt = Instant.parse("2026-08-03T09:00:00Z");

    // SETTLEMENT arrives FIRST (out-of-order): self-heals an UNRECOGNIZED row (loud WARN).
    UUID settleEventId = UUID.randomUUID();
    boolean settled = settle(settleEventId, claimId, orgUnitId, employeeId, settledAt);
    assertThat(settled).isTrue();

    assertThat(claimLedgerRowCountAsAdmin(claimId)).isEqualTo(1L);
    assertThat(isRecognizedAsAdmin(claimId)).isFalse();
    assertThat(isSettledAsAdmin(claimId)).isTrue();
    UUID rowIdAfterSettle = claimLedgerRowIdAsAdmin(claimId);

    // The late APPROVAL arrives: reconciles the SAME row (not a duplicate).
    UUID approveEventId = UUID.randomUUID();
    boolean approved =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                postingService.handleApproved(
                    new ExpenseClaimApprovedEvent(
                        approveEventId,
                        claimId,
                        TENANT_A,
                        orgUnitId,
                        employeeId,
                        Money.ofMinor(250_000L, "IDR"),
                        "supplies",
                        LocalDate.of(2026, 7, 15),
                        approvedAt)));
    assertThat(approved).isTrue();

    assertThat(claimLedgerRowCountAsAdmin(claimId))
        .as("still exactly ONE row — reconciled, not a duplicate")
        .isEqualTo(1L);
    assertThat(claimLedgerRowIdAsAdmin(claimId))
        .as("the SAME row id, reconciled")
        .isEqualTo(rowIdAfterSettle);
    assertThat(isRecognizedAsAdmin(claimId)).isTrue();
    assertThat(isSettledAsAdmin(claimId)).isTrue();

    // 2600 nets to zero: settlement debited it (250,000), approval credited it (250,000).
    assertThat(net2600BalanceAsAdmin()).isEqualTo(0L);
    assertThat(journalEntryCountForSourceEventAsAdmin(settleEventId)).isEqualTo(1L);
    assertThat(journalEntryCountForSourceEventAsAdmin(approveEventId)).isEqualTo(1L);
  }

  // -------------------------------------------------------------------------------------- helpers

  // --------------------------------------------------------------------------------------------
  // Void-before-approval reorder (QA sweep 2026-08-05): the void self-heals a VOIDED row; the late
  // approval reconciles recognition ONTO it — voided_at survives, one row, GL 2600 nets to zero.
  // --------------------------------------------------------------------------------------------

  @Test
  void voidBeforeApprovalSelfHealsAVoidedRowTheLateApprovalReconcilesOnto() throws Exception {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    Instant approvedAt = Instant.parse("2026-08-01T09:00:00Z");
    Instant voidedAt = Instant.parse("2026-08-02T09:00:00Z");

    // T1: the VOID arrives first (cross-topic reorder). It must post its contra AND self-heal a
    // claim-ledger row carrying the void facts — not skip the stamp.
    boolean voided =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                postingService.handleVoided(
                    new ExpenseClaimVoidedEvent(
                        UUID.randomUUID(),
                        claimId,
                        TENANT_A,
                        orgUnitId,
                        employeeId,
                        Money.ofMinor(250_000L, "IDR"),
                        "supplies",
                        approvedAt,
                        voidedAt)));
    assertThat(voided).isTrue();
    assertThat(claimLedgerRowCountAsAdmin(claimId)).isEqualTo(1L);
    assertThat(isVoidedAsAdmin(claimId)).isTrue();
    assertThat(isRecognizedAsAdmin(claimId)).isFalse();

    // T2: the approval finally arrives. It must reconcile recognition ONTO the voided row (one
    // row, voided_at intact) — the old bug inserted a fresh RECOGNIZED row that showed an
    // actually-voided claim as outstanding forever.
    approve(claimId, orgUnitId, employeeId, approvedAt);

    assertThat(claimLedgerRowCountAsAdmin(claimId))
        .as("the approval must reconcile onto the voided row, never insert a second one")
        .isEqualTo(1L);
    assertThat(isVoidedAsAdmin(claimId)).as("voided_at must survive the reconciliation").isTrue();
    assertThat(isRecognizedAsAdmin(claimId)).isTrue();
    assertThat(net2600BalanceAsAdmin())
        .as("recognition credit + void debit on 2600 must net to zero")
        .isZero();
  }

  private void approve(UUID claimId, UUID orgUnitId, UUID employeeId, Instant approvedAt)
      throws Exception {
    boolean posted =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                postingService.handleApproved(
                    new ExpenseClaimApprovedEvent(
                        UUID.randomUUID(),
                        claimId,
                        TENANT_A,
                        orgUnitId,
                        employeeId,
                        Money.ofMinor(250_000L, "IDR"),
                        "supplies",
                        LocalDate.of(2026, 7, 15),
                        approvedAt)));
    assertThat(posted).isTrue();
  }

  private boolean settle(
      UUID eventId, UUID claimId, UUID orgUnitId, UUID employeeId, Instant settledAt)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            postingService.handleSettled(
                settledEvent(eventId, claimId, orgUnitId, employeeId, settledAt)));
  }

  private static ExpenseReimbursementSettledEvent settledEvent(
      UUID eventId, UUID claimId, UUID orgUnitId, UUID employeeId, Instant settledAt) {
    return new ExpenseReimbursementSettledEvent(
        eventId,
        claimId,
        TENANT_A,
        orgUnitId,
        employeeId,
        Money.ofMinor(250_000L, "IDR"),
        "DIRECT",
        null,
        null,
        settledAt);
  }

  /** The number of {@code journal_entry} rows with the given {@code source_event_id}. */
  private long journalEntryCountForSourceEventAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM journal_entry WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The number of {@code employee_expense_claim_ledger} rows for the given claim (any tenant). */
  private long claimLedgerRowCountAsAdmin(UUID claimId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM employee_expense_claim_ledger WHERE claim_id = ?")) {
      ps.setObject(1, claimId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The single {@code employee_expense_claim_ledger.id} for a claim (assumes exactly one row). */
  private UUID claimLedgerRowIdAsAdmin(UUID claimId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM employee_expense_claim_ledger WHERE claim_id = ?")) {
      ps.setObject(1, claimId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private boolean isRecognizedAsAdmin(UUID claimId) throws Exception {
    return booleanColumnAsAdmin(claimId, "recognized_at IS NOT NULL");
  }

  private boolean isSettledAsAdmin(UUID claimId) throws Exception {
    return booleanColumnAsAdmin(claimId, "settled_at IS NOT NULL");
  }

  private boolean isVoidedAsAdmin(UUID claimId) throws Exception {
    return booleanColumnAsAdmin(claimId, "voided_at IS NOT NULL");
  }

  private boolean booleanColumnAsAdmin(UUID claimId, String predicateExpression) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT "
                    + predicateExpression
                    + " FROM employee_expense_claim_ledger"
                    + " WHERE claim_id = ?")) {
      ps.setObject(1, claimId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  /** The {@code amount_minor} for a claim under a specific tenant (admin bypass, cross-tenant). */
  private long amountMinorForTenantAsAdmin(UUID claimId, String companyId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT amount_minor FROM employee_expense_claim_ledger"
                    + " WHERE claim_id = ? AND company_id = ?")) {
      ps.setObject(1, claimId);
      ps.setString(2, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** Σdebit − Σcredit for account 2600 across every {@code journal_line} (this test truncates). */
  private long net2600BalanceAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0)"
                    + " FROM journal_line WHERE account_code = '2600'")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
