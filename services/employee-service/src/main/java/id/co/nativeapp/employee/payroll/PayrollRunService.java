package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a payroll run for the bound company (design §5). It holds no {@code @Transactional}
 * and no DB access — the transactional units of work (gate, freeze, compute, allocate, persist,
 * emit) live in {@link PayrollRunWriter} so the Spring proxy + auto-RLS aspect engage (the {@code
 * *Writer} pattern). {@code company_id} is stamped by the writer from {@link TenantContext}, never
 * from the request (rule 5).
 *
 * <p>The run's {@code base_currency} is the company's functional currency (immutable once
 * transactions exist — set at company creation). In this slice it is supplied with the request (the
 * dashboard reads it from the company; it is never toggled here); the writer asserts every input is
 * in that single currency (no implicit FX — finance owns FX).
 */
@Service
public class PayrollRunService {

  private final PayrollRunWriter writer;

  public PayrollRunService(PayrollRunWriter writer) {
    this.writer = writer;
  }

  /**
   * Calculates a new run for the period (gates on completeness, freezes the rule set, computes
   * gross-to-net + allocation), leaving it CALCULATED.
   *
   * <p>If the calculate transaction aborts (currency mismatch, conflicting legal employer, a
   * duplicate effective statutory rule, etc.) its run row rolls back, so this records a FAILED
   * audit row in a SEPARATE transaction (through the writer proxy, so {@code REQUIRES_NEW} engages)
   * before rethrowing — a failed attempt always leaves a trace.
   */
  public PayrollRun calculate(RunPayrollCommand command, String baseCurrency) {
    TenantContext.require();
    try {
      return writer.calculate(command, baseCurrency);
    } catch (RuntimeException failure) {
      writer.recordFailedAttempt(command.period(), baseCurrency);
      throw failure;
    }
  }

  /** Posts a CALCULATED run (emits PayrollPosted + LaborCostAllocated via the outbox). */
  public PayrollRun post(UUID runId) {
    TenantContext.require();
    return writer.post(runId);
  }

  /** Calculates then posts in one call (the common "run and post" path). */
  public PayrollRun calculateAndPost(RunPayrollCommand command, String baseCurrency) {
    PayrollRun calculated = calculate(command, baseCurrency);
    return post(calculated.getId());
  }
}
