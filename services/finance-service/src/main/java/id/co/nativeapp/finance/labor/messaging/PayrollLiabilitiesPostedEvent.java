package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The decoded {@code PayrollLiabilitiesPosted} event (ADR 0032, Track P phase P4) — the run-level
 * liability recognition. {@link PayrollLiabilitiesPostedListener} hands one of these to {@code
 * PayrollLiabilityWriter}, which books ONE balanced ad-hoc journal entry: Dr {@code LABOR_CLEARING}
 * (6900) for {@link #employerCostTotal()} / Cr one leg per non-zero {@link #liabilities()} bucket
 * (a negative bucket posts as the opposite, Dr, side instead).
 *
 * <p>Company-level totals only (no PII — rule 6). All money is {@link Money} (never a float, rule
 * 8). {@code runType} defaults to {@code "REGULAR"} on the wire until Track P phase P8 (THR).
 *
 * @param eventId the source event UUID (idempotency key)
 * @param companyId the owning tenant the consumer binds the handler to
 * @param payrollRunId the owning payroll run
 * @param runSeq the run sequence (the supersession signal, scoped within {@code runType})
 * @param runType the payroll run type ({@code REGULAR} today; THR lands at Track P phase P8)
 * @param period the run's accounting period {@code YYYY-MM}
 * @param baseCurrency the ISO-4217 base currency every amount is denominated in
 * @param employerCostTotal the run's total employer-borne labor cost — the Dr {@code
 *     LABOR_CLEARING} leg
 * @param liabilities the non-zero liability buckets — together with {@link #employerCostTotal()} as
 *     the balancing Dr leg, a balanced double-entry set (asserted by the producer)
 * @param usesIllustrativeRules whether the run was computed against illustrative-placeholder
 *     figures
 * @param postedAt when the run posted
 */
public record PayrollLiabilitiesPostedEvent(
    UUID eventId,
    String companyId,
    UUID payrollRunId,
    int runSeq,
    String runType,
    String period,
    String baseCurrency,
    Money employerCostTotal,
    List<LiabilityBucket> liabilities,
    boolean usesIllustrativeRules,
    Instant postedAt) {

  /**
   * One liability bucket: {@code liabilityRole} is one of {@code NET_WAGES_PAYABLE}, {@code
   * PPH21_PAYABLE}, {@code BPJS_KES_PAYABLE}, {@code BPJS_TK_PAYABLE}, {@code
   * OTHER_DEDUCTIONS_PAYABLE} — resolved to an {@link
   * id.co.nativeapp.finance.gl.domain.AccountRole} by name; an unrecognised role fails safe to
   * {@code SUSPENSE} (money never dropped, HR-3). {@code amount} is normally positive (a Cr leg);
   * it MAY be negative for {@code PPH21_PAYABLE} in the December Art-17 true-up refund month (ADR
   * 0031), in which case it posts as a Dr leg for its absolute value instead (ADR 0032).
   */
  public record LiabilityBucket(String liabilityRole, Money amount) {}
}
