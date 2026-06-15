package id.co.nativeapp.employee.payroll.domain;

/**
 * The lifecycle state of a {@link PayrollRun} (design §5):
 *
 * <pre>
 *   DRAFT -> SEALED_GATE_PENDING -> CALCULATING -> CALCULATED -> POSTED
 *                                              \-> FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@code DRAFT} — created, not yet gated.
 *   <li>{@code SEALED_GATE_PENDING} — waiting on the completeness gate (every expected source
 *       business_id must have sealed the period via {@code PeriodSealed}).
 *   <li>{@code CALCULATING} — the gate passed; the engine is computing gross-to-net.
 *   <li>{@code CALCULATED} — computed and persisted, ready to post.
 *   <li>{@code POSTED} — totals committed; {@code PayrollPosted} + {@code LaborCostAllocated}
 *       emitted via the outbox in the SAME transaction that flips to POSTED. Only a {@code
 *       CALCULATED -> POSTED} transition emits, so a retried post cannot double-emit.
 *   <li>{@code FAILED} — a fatal condition (currency mismatch, conflicting legal employer,
 *       allocation sum mismatch) failed the run.
 * </ul>
 */
public enum RunStatus {
  DRAFT,
  SEALED_GATE_PENDING,
  CALCULATING,
  CALCULATED,
  POSTED,
  FAILED
}
