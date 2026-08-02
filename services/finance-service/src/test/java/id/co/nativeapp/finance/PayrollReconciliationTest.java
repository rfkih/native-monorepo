package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollPostedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.labor.service.PayrollReconciliationService;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Reconciliation proofs (#23): the sum of a run's {@code LaborCostAllocated} buckets (including the
 * unallocated bucket) must equal the {@code PayrollPosted} labor control total ({@code
 * employer_contribution + gross}). A match marks the run {@code RECONCILED}; a mismatch marks it
 * {@code RECONCILE_FAILED} (loud, postings stay on the books). Events can arrive in any order —
 * buckets-then-PayrollPosted and PayrollPosted-then-buckets both reconcile correctly.
 */
@SpringBootTest
class PayrollReconciliationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNALLOCATED_OUTLET = new UUID(0L, 0L);

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PayrollReconciliationService reconciliationService;

  @Test
  void bucketSumMatchingTheControlTotalMarksTheRunReconciled() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();
    // gross 40,000,000 + employer 800,000 = control 40,800,000. Two outlet buckets of 20,400,000.
    long bucket = 20_400_000L;

    laborService.handle(
        bucket(UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", bucket, false, occurredAt));
    laborService.handle(
        bucket(
            UUID.randomUUID(),
            run,
            period,
            UNALLOCATED_OUTLET,
            "9999-UNALLOCATED-LABOR",
            bucket,
            true,
            occurredAt));

    assertThat(reconciliationService.handle(payroll(run, period, 40_000_000L, 800_000L))).isTrue();

    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILED");
  }

  @Test
  void bucketSumNotMatchingTheControlTotalMarksTheRunReconcileFailed() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();

    // Only ONE bucket of 20,400,000 arrives (a partial run), but the control total is 40,800,000.
    laborService.handle(
        bucket(
            UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", 20_400_000L, false, occurredAt));

    assertThat(reconciliationService.handle(payroll(run, period, 40_000_000L, 800_000L))).isTrue();

    // Mismatch flagged loudly; the posting that DID land is NOT torn down (money stays on the
    // books).
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED");
    assertThat(ledgerCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void payrollPostedArrivingBeforeAnyBucketReconcilesOnceTheBucketArrives() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();

    // PayrollPosted FIRST (out of order): the run row opens PENDING with the control total
    // recorded.
    assertThat(reconciliationService.handle(payroll(run, period, 5_000_000L, 200_000L))).isTrue();
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED"); // 0 != 5,200,000 yet

    // The single bucket (== the control total 5,200,000) arrives next.
    laborService.handle(
        bucket(
            UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", 5_200_000L, false, occurredAt));

    // Re-deliver PayrollPosted under a NEW event id (the producer's at-least-once redelivery): now
    // the accumulated sum matches and the run reconciles.
    PayrollPostedEvent redelivered =
        new PayrollPostedEvent(
            UUID.randomUUID(),
            TENANT_A,
            run,
            1,
            "REGULAR",
            period,
            "IDR",
            Money.ofMinor(5_000_000L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(200_000L, "IDR"),
            Money.ofMinor(4_800_000L, "IDR"),
            false,
            occurredAt);
    assertThat(reconciliationService.handle(redelivered)).isTrue();
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILED");
  }

  @Test
  void payrollPostedFirstThenAllBucketsSelfHealsToReconciledWithoutARedelivery() throws Exception {
    // FIX 4 (#23): PayrollPosted arrives BEFORE the buckets, so the run opens RECONCILE_FAILED (0
    // !=
    // control). As each bucket lands, the bucket path re-drives reconciliation, so the run
    // self-heals to RECONCILED on the FINAL bucket — WITHOUT any PayrollPosted re-delivery.
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();
    long bucket = 20_400_000L; // two buckets == control 40,800,000

    // PayrollPosted FIRST: control total recorded, no buckets yet -> RECONCILE_FAILED.
    assertThat(reconciliationService.handle(payroll(run, period, 40_000_000L, 800_000L))).isTrue();
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED");

    // First bucket: still short of the control total -> stays RECONCILE_FAILED (self-heal not yet).
    laborService.handle(
        bucket(UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", bucket, false, occurredAt));
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED");

    // FINAL bucket completes the sum: the run self-heals to RECONCILED with NO PayrollPosted
    // re-delivery.
    laborService.handle(
        bucket(
            UUID.randomUUID(),
            run,
            period,
            UNALLOCATED_OUTLET,
            "9999-UNALLOCATED-LABOR",
            bucket,
            false,
            occurredAt));
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILED");
  }

  @Test
  void aLatePayrollPostedForAnAlreadySupersededRunLeavesItSuperseded() throws Exception {
    // FIX 3 (#23): SUPERSEDED is TERMINAL. A run that a higher-seq run already reversed must NOT be
    // re-reconciled by a late PayrollPosted (which would clobber SUPERSEDED -> RECONCILED,
    // displaying
    // a reversed run as reconciled — misleading).
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    // run_seq=1 lands, then run_seq=2 supersedes it (run 1 -> SUPERSEDED).
    laborService.handle(
        bucket(
            UUID.randomUUID(),
            run1,
            period,
            OUTLET,
            "5100-SALARY",
            20_000_000L,
            false,
            occurredAt));
    laborService.handle(
        bucket2(
            UUID.randomUUID(), run2, 2, period, OUTLET, "5100-SALARY", 22_000_000L, occurredAt));
    assertThat(runStateAsAdmin(run1)).isEqualTo("SUPERSEDED");

    // A late PayrollPosted for the OLD run 1 arrives (even one whose totals would "reconcile"): it
    // must leave run 1 SUPERSEDED, not flip it to RECONCILED.
    assertThat(reconciliationService.handle(payroll(run1, period, 20_000_000L, 0L))).isTrue();
    assertThat(runStateAsAdmin(run1))
        .as("SUPERSEDED is terminal — a late PayrollPosted does not clobber it")
        .isEqualTo("SUPERSEDED");
  }

  @Test
  void aCurrencyDivergenceIsStickyAndCannotSelfHealToReconciled() throws Exception {
    // FIX 2 (#23, no-FX scope): a once-seen currency divergence is TERMINAL. PayrollPosted(IDR)
    // first, then an IDR bucket, then a DIVERGENT USD bucket (dropped, run -> CURRENCY_MISMATCH),
    // then more IDR buckets that COMPLETE the IDR control sum. The completing bucket must NOT
    // self-heal the run back to RECONCILED — that would mask that a USD cost was observed and
    // dropped. The divergence stays sticky.
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();
    long idrBucket = 20_400_000L; // two IDR buckets == control 40,800,000

    // PayrollPosted FIRST: control total recorded (IDR), no buckets yet -> RECONCILE_FAILED.
    assertThat(reconciliationService.handle(payroll(run, period, 40_000_000L, 800_000L))).isTrue();
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED");

    // First IDR bucket: still short of the control total -> stays RECONCILE_FAILED.
    laborService.handle(
        bucket(
            UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", idrBucket, false, occurredAt));
    assertThat(runStateAsAdmin(run)).isEqualTo("RECONCILE_FAILED");

    // A DIVERGENT USD bucket lands: it is not posted/accumulated and flips the run to the terminal
    // CURRENCY_MISMATCH (a divergent cost was observed and dropped).
    laborService.handle(
        bucketCurrency(
            UUID.randomUUID(),
            run,
            period,
            OUTLET,
            "5100-SALARY",
            Money.ofMinor(1_000L, "USD"),
            occurredAt));
    assertThat(runStateAsAdmin(run)).isEqualTo("CURRENCY_MISMATCH");

    // The SECOND IDR bucket COMPLETES the IDR control sum (40.8M). Without sticky-divergence this
    // would self-heal to RECONCILED — but the divergence is terminal, so the run STAYS
    // CURRENCY_MISMATCH (never RECONCILED), keeping the dropped USD cost loud.
    laborService.handle(
        bucket(
            UUID.randomUUID(), run, period, OUTLET, "5100-SALARY", idrBucket, false, occurredAt));
    assertThat(runStateAsAdmin(run))
        .as(
            "currency divergence is sticky — a completing IDR bucket cannot self-heal to RECONCILED")
        .isEqualTo("CURRENCY_MISMATCH");

    // Only the two IDR buckets are on the books; the USD bucket was never posted.
    assertThat(ledgerCountAsAdmin()).isEqualTo(2L);
  }

  // ----------------------------------------------------------------------- helpers

  private LaborCostAllocatedEvent bucketCurrency(
      UUID eventId,
      UUID runId,
      String period,
      UUID outlet,
      String glAccount,
      Money amount,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        1,
        "REGULAR",
        period,
        outlet,
        glAccount,
        amount,
        false,
        false,
        occurredAt);
  }

  private LaborCostAllocatedEvent bucket2(
      UUID eventId,
      UUID runId,
      int runSeq,
      String period,
      UUID outlet,
      String glAccount,
      long amountMinor,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        "REGULAR",
        period,
        outlet,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        false,
        false,
        occurredAt);
  }

  private LaborCostAllocatedEvent bucket(
      UUID eventId,
      UUID runId,
      String period,
      UUID outlet,
      String glAccount,
      long amountMinor,
      boolean unallocated,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        1,
        "REGULAR",
        period,
        outlet,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        false,
        unallocated,
        occurredAt);
  }

  private PayrollPostedEvent payroll(
      UUID runId, String period, long grossMinor, long employerMinor) {
    return new PayrollPostedEvent(
        UUID.randomUUID(),
        TENANT_A,
        runId,
        1,
        "REGULAR",
        period,
        "IDR",
        Money.ofMinor(grossMinor, "IDR"),
        Money.ofMinor(0L, "IDR"),
        Money.ofMinor(employerMinor, "IDR"),
        Money.ofMinor(grossMinor - employerMinor, "IDR"),
        false,
        Instant.parse("2026-06-30T12:00:00Z"));
  }
}
