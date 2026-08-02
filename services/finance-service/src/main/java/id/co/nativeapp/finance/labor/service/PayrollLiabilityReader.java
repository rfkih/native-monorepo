package id.co.nativeapp.finance.labor.service;

import id.co.nativeapp.finance.labor.dto.PayrollLiabilityBucketResponse;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityRunResponse;
import id.co.nativeapp.finance.labor.projection.PayrollLiabilityRunView;
import id.co.nativeapp.finance.labor.projection.PayrollSettlementSummaryView;
import id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository;
import id.co.nativeapp.finance.labor.repository.PayrollSettlementRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service for {@code GET /api/v1/payroll-liabilities?period=} (ADR 0032, Track P phase
 * P5): every ACTIVE run's five liability buckets + their settlement status, combined from two
 * narrow projections — {@link PayrollLiabilityRunView} (the bucket totals, reconstructed from the
 * run's liability {@code journal_entry}; see its javadoc for the read design) and {@link
 * PayrollSettlementSummaryView} (which buckets are already settled). Console HR reads this
 * finance-service endpoint DIRECTLY — the established cross-service pattern (finance owns the
 * books; no synchronous call between business services, rule 2).
 *
 * <p>The number of runs a single company has for a single period is bounded (one row per {@code
 * run_seq} correction) and in practice never approaches the 1000-row IN-chunking threshold
 * (CLAUDE.md); the settlement-status batch read below passes the full run-ledger id list in one
 * query rather than chunking with {@code Lists.partition} (a documented, deliberately bounded-scope
 * simplification — see the phase report).
 */
@Service
public class PayrollLiabilityReader {

  private static final List<String> BUCKET_ORDER =
      List.of("NET_WAGES", "PPH21", "BPJS_KES", "BPJS_TK", "OTHER");

  private final PayrollRunLedgerRepository runLedgerRepository;
  private final PayrollSettlementRepository settlementRepository;

  public PayrollLiabilityReader(
      PayrollRunLedgerRepository runLedgerRepository,
      PayrollSettlementRepository settlementRepository) {
    this.runLedgerRepository = runLedgerRepository;
    this.settlementRepository = settlementRepository;
  }

  /** Every ACTIVE run's liability buckets + settlement status for {@code period}, newest first. */
  @Transactional(readOnly = true)
  public List<PayrollLiabilityRunResponse> forPeriod(String period) {
    List<PayrollLiabilityRunView> runs = runLedgerRepository.findLiabilityRunsForPeriod(period);
    if (runs.isEmpty()) {
      return List.of();
    }

    List<UUID> runLedgerIds = runs.stream().map(PayrollLiabilityRunView::getRunLedgerId).toList();
    Map<String, PayrollSettlementSummaryView> settlementsByRunAndKind = new HashMap<>();
    for (PayrollSettlementSummaryView settlement :
        settlementRepository.findSummariesByPayrollRunLedgerIdIn(runLedgerIds)) {
      settlementsByRunAndKind.put(
          settlement.getPayrollRunLedgerId() + ":" + settlement.getKind(), settlement);
    }

    List<PayrollLiabilityRunResponse> result = new ArrayList<>(runs.size());
    for (PayrollLiabilityRunView run : runs) {
      result.add(toResponse(run, settlementsByRunAndKind));
    }
    return result;
  }

  /**
   * One run's liability buckets + settlement status by its {@code payroll_run_ledger} id — backs
   * {@code GET /api/v1/payroll-liabilities/{runLedgerId}} and the settle response/{@code Location}.
   */
  @Transactional(readOnly = true)
  public Optional<PayrollLiabilityRunResponse> forRunLedger(UUID runLedgerId) {
    Optional<PayrollLiabilityRunView> run =
        runLedgerRepository.findLiabilityRunByRunLedgerId(runLedgerId);
    if (run.isEmpty()) {
      return Optional.empty();
    }
    Map<String, PayrollSettlementSummaryView> settlementsByRunAndKind = new HashMap<>();
    for (PayrollSettlementSummaryView settlement :
        settlementRepository.findSummariesByPayrollRunLedgerIdIn(List.of(runLedgerId))) {
      settlementsByRunAndKind.put(
          settlement.getPayrollRunLedgerId() + ":" + settlement.getKind(), settlement);
    }
    return Optional.of(toResponse(run.get(), settlementsByRunAndKind));
  }

  private PayrollLiabilityRunResponse toResponse(
      PayrollLiabilityRunView run, Map<String, PayrollSettlementSummaryView> settlements) {
    Map<String, Long> bucketAmounts =
        Map.of(
            "NET_WAGES", run.getNetWagesMinor(),
            "PPH21", run.getPph21Minor(),
            "BPJS_KES", run.getBpjsKesMinor(),
            "BPJS_TK", run.getBpjsTkMinor(),
            "OTHER", run.getOtherMinor());

    List<PayrollLiabilityBucketResponse> buckets = new ArrayList<>(BUCKET_ORDER.size());
    for (String kind : BUCKET_ORDER) {
      long amountMinor = bucketAmounts.get(kind);
      PayrollSettlementSummaryView settlement = settlements.get(run.getRunLedgerId() + ":" + kind);
      buckets.add(
          new PayrollLiabilityBucketResponse(
              kind,
              amountMinor,
              run.getCurrency(),
              amountMinor < 0L,
              settlement != null,
              settlement == null ? null : settlement.getSettledAt(),
              settlement == null ? null : settlement.getJournalEntryId()));
    }

    return new PayrollLiabilityRunResponse(
        run.getRunLedgerId(),
        run.getPayrollRunId(),
        run.getPeriod(),
        run.getRunSeq(),
        run.getRunType(),
        run.getCurrency(),
        buckets);
  }
}
