package id.co.nativeapp.employee.payroll.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.domain.ConflictingLegalEmployerException;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.projection.LinkedClaimIdView;
import id.co.nativeapp.employee.expense.projection.LinkedClaimTotalView;
import id.co.nativeapp.employee.expense.service.ExpenseClaimPayrollLinker;
import id.co.nativeapp.employee.org.domain.OrgUnitProjection;
import id.co.nativeapp.employee.org.repository.OrgUnitProjectionRepository;
import id.co.nativeapp.employee.payroll.domain.AllocationInputs.OutletShare;
import id.co.nativeapp.employee.payroll.domain.AllocationInputs.PersonAllocation;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.domain.IncompletePeriodException;
import id.co.nativeapp.employee.payroll.domain.LaborCostAllocation;
import id.co.nativeapp.employee.payroll.domain.MetricInput;
import id.co.nativeapp.employee.payroll.domain.NonMonthlyCompensationException;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayFrequency;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.AnnualContext;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.DeductionInput;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.EarningInput;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.domain.PendingWorkEntriesException;
import id.co.nativeapp.employee.payroll.domain.RunStatus;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.domain.TaxableReimbursementComponentException;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.ComputedLine;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.PersonResult;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.messaging.LaborCostAllocatedSchema;
import id.co.nativeapp.employee.payroll.messaging.PayrollLiabilitiesPostedSchema;
import id.co.nativeapp.employee.payroll.messaging.PayrollPostedSchema;
import id.co.nativeapp.employee.payroll.repository.CompensationPackageRepository;
import id.co.nativeapp.employee.payroll.repository.EarningRuleRepository;
import id.co.nativeapp.employee.payroll.repository.LaborCostAllocationRepository;
import id.co.nativeapp.employee.payroll.repository.MetricInputRepository;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.PayrollRunRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.employee.payroll.repository.PeriodSealRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import id.co.nativeapp.employee.payroll.service.LaborCostAllocator.AllocatedRow;
import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import id.co.nativeapp.employee.timeoff.projection.ApprovedOvertimeView;
import id.co.nativeapp.employee.timeoff.projection.ApprovedUnpaidLeaveView;
import id.co.nativeapp.employee.timeoff.repository.LeaveRequestRepository;
import id.co.nativeapp.employee.timeoff.repository.OvertimeEntryRepository;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarWriter;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for a payroll run (design §2/§4/§5). A distinct
 * bean (the {@code *Writer} pattern) so each method is invoked through the Spring proxy and the
 * {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5); the whole compute runs in ONE
 * tenant-scoped transaction so a forgotten WHERE cannot leak across companies.
 *
 * <p><strong>calculate</strong> gates on completeness (PeriodSealed), freezes the statutory rule
 * set (HR-7) and sets the illustrative flag + WARN (HR-9), runs the pure {@link
 * GrossToNetCalculator} per person (persisting encrypted {@link PayslipLine}s), then the pure
 * {@link LaborCostAllocator} (persisting {@link LaborCostAllocation}s, asserting exact-sum), and
 * stores the company-level totals. <strong>post</strong> flips CALCULATED -&gt; POSTED and emits
 * {@code PayrollPosted} + aggregated {@code LaborCostAllocated} via the outbox in the SAME
 * transaction (rule 3) — only that transition emits, so a retried post cannot double-emit.
 *
 * <p><strong>December / final-month Art-17 true-up (Track P phase P3).</strong> When a run's period
 * is December AND the frozen rule set resolves an {@code ANNUAL_PROGRESSIVE} rule ({@code
 * PPH21_ARTICLE17} in the OFFICIAL dataset), {@code calculate} swaps every person's monthly
 * income-tax component onto the annual rule (see {@link #swapForAnnualTrueUp}) and builds each
 * person's year-to-date {@link id.co.nativeapp.employee.payroll.domain.PayrollInputs.AnnualContext}
 * by decrypting and summing their ACTIVE prior-period payslip lines this fiscal year (see {@link
 * #buildAnnualContext}) — no separate accumulator table; the immutable payslip-line history is the
 * single source of truth. A non-December run, or a December run for a tenant with no resolved
 * annual rule, is completely unaffected (byte-identical to a pre-P3 run).
 *
 * <p>PII (salary) is encrypted at rest on the lines and is NEVER logged or evented; events carry
 * only company totals / outlet-GL buckets (rule 6).
 *
 * <p><strong>Expense-claim seam (ADR 0030 §6, Track E phase E5).</strong> {@code calculate} opens
 * by calling {@link ExpenseClaimPayrollLinker#releaseForPeriod}, passing its OWN {@code runSeq}
 * (W3, E5 review — the corrective run releases/re-links a superseded claim in its OWN cycle, never
 * a run later), then {@link ExpenseClaimPayrollLinker#linkForRun} — both {@code propagation =
 * MANDATORY}, joining THIS method's own transaction — so a stale/superseded claim link is freed and
 * every eligible claim is atomically linked to the run being calculated. {@code post} closes by
 * calling {@link ExpenseClaimPayrollLinker#markReimbursedAndEmit}, which is E5-TRANSITIONALLY
 * gated: it no-ops unless the run actually carries an {@code EXPENSE_REIMBURSEMENT} payslip line —
 * now LIVE (Track P Phase P7), see below.
 *
 * <p><strong>Work inputs (Track P Phase P7).</strong> After linking claims, {@code calculate}
 * resolves this run's work inputs: (1) a pending-entries GATE ({@link
 * #requireNoPendingWorkEntries}) rejects the WHOLE run (409) if any employee has an undecided
 * SUBMITTED leave/overtime request this period; (2) per employee, {@link #appendWorkInputs}
 * synthesizes a SIGNED-NEGATIVE TAXABLE {@code UNPAID_LEAVE} earning (base × approved unpaid days /
 * the tenant {@link WorkCalendar}'s divisor), a TAXABLE {@code OVERTIME} earning (PP 35/2021 tiers
 * via {@link WorkInputCalculator}), and a NON-taxable {@code EXPENSE_REIMBURSEMENT} earning (the
 * linked claim total from {@link ExpenseClaimPayrollLinker#findLinkedClaimTotalsByEmployee}) — each
 * independently gated on its {@code pay_component} catalog row actually existing (a tenant that has
 * not activated the {@code ID-2026.2} dataset top-up sees NOTHING different, byte-identical to a
 * pre-P7 run); (3) everything consumed (entry/request/claim ids, derived days/minutes/amounts) is
 * FROZEN onto {@code payroll_run.work_inputs_json} (the {@code sealed_sources_json} reproducibility
 * precedent). {@code EXPENSE_REIMBURSEMENT} lifts {@code grossEarnings}/net but is EXCLUDED from
 * employer labor cost, {@code LaborCostAllocated} buckets, and the liability {@code
 * NET_WAGES_PAYABLE} bucket (the expense was already recognized Dr expense / Cr {@code 2600} at
 * claim APPROVAL — see {@link #computeLiabilityBuckets}'s Javadoc for the full
 * NET_WAGES_PAYABLE-split identity proof, the crux of this phase). {@link #laborCostByGlAccount}
 * also SPLITS the {@code LaborCostAllocated} buckets per component GL account (5100/5130/5200/...)
 * instead of collapsing everything onto BASE's — an allocation-engine change only, no schema/event
 * change.
 */
@Component
public class PayrollRunWriter {

  private static final Logger log = LoggerFactory.getLogger(PayrollRunWriter.class);

  /**
   * The sentinel "outlet" (and legal employer) for the UNALLOCATED suspense bucket — the all-zeros
   * UUID, deliberately not a real org unit. An employee with no outlet assignment in the period (on
   * leave / between assignments) has their employer labor cost routed here so the run still
   * completes and the exact-sum invariant holds, while finance sees the cost VISIBLY marked rather
   * than silently dropped.
   */
  public static final UUID UNALLOCATED_OUTLET = new UUID(0L, 0L);

  /** The suspense GL account the UNALLOCATED bucket posts to (finance must clear it). */
  public static final String UNALLOCATED_GL_ACCOUNT = "9999-UNALLOCATED-LABOR";

  private final PayrollRunRepository runRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final LaborCostAllocationRepository allocationRepository;
  private final CompensationPackageRepository compPackageRepository;
  private final EarningRuleRepository earningRuleRepository;
  private final PayComponentRepository payComponentRepository;
  private final StatutoryRuleRepository statutoryRuleRepository;
  private final MetricInputRepository metricInputRepository;
  private final PeriodSealRepository periodSealRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmployeeRepository employeeRepository;
  private final OrgUnitProjectionRepository orgUnitProjectionRepository;
  private final GrossToNetCalculator calculator;
  private final LaborCostAllocator allocator;
  private final OutboxWriter outboxWriter;
  private final Clock clock;
  private final ExpenseClaimPayrollLinker expenseClaimPayrollLinker;
  private final LeaveRequestRepository leaveRequestRepository;
  private final OvertimeEntryRepository overtimeEntryRepository;
  private final WorkCalendarWriter workCalendarWriter;
  private final WorkInputCalculator workInputCalculator;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PayrollRunWriter(
      PayrollRunRepository runRepository,
      PayslipLineRepository payslipLineRepository,
      LaborCostAllocationRepository allocationRepository,
      CompensationPackageRepository compPackageRepository,
      EarningRuleRepository earningRuleRepository,
      PayComponentRepository payComponentRepository,
      StatutoryRuleRepository statutoryRuleRepository,
      MetricInputRepository metricInputRepository,
      PeriodSealRepository periodSealRepository,
      AssignmentRepository assignmentRepository,
      EmployeeRepository employeeRepository,
      OrgUnitProjectionRepository orgUnitProjectionRepository,
      GrossToNetCalculator calculator,
      LaborCostAllocator allocator,
      OutboxWriter outboxWriter,
      Clock clock,
      ExpenseClaimPayrollLinker expenseClaimPayrollLinker,
      LeaveRequestRepository leaveRequestRepository,
      OvertimeEntryRepository overtimeEntryRepository,
      WorkCalendarWriter workCalendarWriter,
      WorkInputCalculator workInputCalculator) {
    this.runRepository = runRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.allocationRepository = allocationRepository;
    this.compPackageRepository = compPackageRepository;
    this.earningRuleRepository = earningRuleRepository;
    this.payComponentRepository = payComponentRepository;
    this.statutoryRuleRepository = statutoryRuleRepository;
    this.metricInputRepository = metricInputRepository;
    this.periodSealRepository = periodSealRepository;
    this.assignmentRepository = assignmentRepository;
    this.employeeRepository = employeeRepository;
    this.orgUnitProjectionRepository = orgUnitProjectionRepository;
    this.calculator = calculator;
    this.allocator = allocator;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
    this.expenseClaimPayrollLinker = expenseClaimPayrollLinker;
    this.leaveRequestRepository = leaveRequestRepository;
    this.overtimeEntryRepository = overtimeEntryRepository;
    this.workCalendarWriter = workCalendarWriter;
    this.workInputCalculator = workInputCalculator;
  }

  /** {@code pay_component.component_key} for the OVERTIME earning (Track P Phase P7). */
  static final String COMPONENT_KEY_OVERTIME = "OVERTIME";

  /** {@code pay_component.component_key} for the UNPAID_LEAVE earning (Track P Phase P7). */
  static final String COMPONENT_KEY_UNPAID_LEAVE = "UNPAID_LEAVE";

  /**
   * IN-clause chunk size (CLAUDE.md: chunk at ≤1000) — mirrors {@code ExpenseClaimPayrollLinker}.
   */
  private static final int CHUNK_SIZE = 1000;

  /**
   * Calculates a new payroll run for the period: gates on completeness, freezes the rule set,
   * computes gross-to-net + allocation, and leaves the run CALCULATED (ready to post). Returns the
   * persisted run.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayrollRun calculate(RunPayrollCommand command, String baseCurrency) {
    String tenant = TenantContext.require().companyId();
    LocalDate asOf = YearMonth.parse(command.period()).atEndOfMonth();

    // Track P Phase P7 — the pending-work-entries gate, FIRST: any SUBMITTED (undecided)
    // leave_request/overtime_entry for one of this run's employees in this period blocks the WHOLE
    // run before any other work happens — pay must never silently ignore a request nobody has
    // decided yet.
    requireNoPendingWorkEntries(command.employeeIds(), command.period());

    // Completeness gate (ARCHITECTURE.md §4): every expected source must have sealed the period.
    boolean ungated = enforceCompleteness(command);

    int runSeq = nextRunSeq(command.period());
    PayrollRun run = new PayrollRun(command.period(), runSeq, baseCurrency);
    run.setCompanyId(tenant);
    run.markGatePending();
    recordSealedLedger(run, command.period());
    run.markCalculating();

    // ADR 0030 §6 (Track E phase E5, W3 fix — E5 review) — the payroll<->expense-claim seam,
    // joined to THIS transaction (ExpenseClaimPayrollLinker is propagation MANDATORY). release
    // FIRST, passing THIS run's own runSeq: a prior POSTED run of this SAME period with a lower
    // run_seq is, by construction, superseded by the run now calculating, so its claims are
    // released AND re-linked to THIS run in the SAME cycle — no lagged third run needed (see the
    // linker's class Javadoc). link SECOND: atomically claims every un-linked APPROVED+PAYROLL
    // claim for this run's employees.
    // NOTE (Track P Phase P7 is LIVE): a claim linked here rides the EXPENSE_REIMBURSEMENT earning
    // this SAME calculate() call appends further down — PROVIDED the tenant has activated a
    // dataset that carries the EXPENSE_REIMBURSEMENT catalog component (ID-2026.2+). If it has not
    // (or a component is otherwise missing), post()'s markReimbursedAndEmit call correctly finds no
    // such payslip line and leaves these claims APPROVED+linked (the E5-transitional fallback,
    // still load-bearing for that case — see ExpenseClaimPayrollLinker's class Javadoc) rather than
    // settling money the employee never actually received via this run.
    expenseClaimPayrollLinker.releaseForPeriod(command.period(), runSeq);
    expenseClaimPayrollLinker.linkForRun(run.getId(), command.period(), command.employeeIds());

    // Track P Phase P7 — resolve the run's work inputs: the linked expense-claim reimbursement
    // totals (read AFTER linking, in THIS SAME transaction, so this run's own just-linked claims
    // are visible — READ COMMITTED sees a prior statement's own uncommitted writes), the tenant's
    // work calendar (seeded on first use if missing — the P6 lazy-seed precedent, called explicitly
    // here rather than assumed already seeded), and every APPROVED unpaid-leave/overtime row for
    // this run's employees in this period.
    Map<UUID, ReimbursementInfo> reimbursementByEmployee =
        reimbursementInfoByEmployee(run.getId(), baseCurrency);
    WorkCalendar calendar = workCalendarWriter.seedDefaultIfMissing();
    Map<UUID, List<ApprovedUnpaidLeaveView>> unpaidByEmployee =
        approvedUnpaidLeaveByEmployee(command.employeeIds(), command.period());
    Map<UUID, List<ApprovedOvertimeView>> overtimeByEmployee =
        approvedOvertimeByEmployee(command.employeeIds(), command.period());

    if (ungated) {
      // An ungated run must NEVER be silent (no expected-source set means the completeness gate
      // could not run, so a period that is not actually closed could still be paid). NO PII. The
      // server-side expected-source registry is a tracked follow-up.
      log.warn(
          "payroll_run {} period {} running WITHOUT a completeness gate — no expected sources"
              + " supplied",
          run.getId(),
          run.getPeriod());
    }

    // Freeze the effective statutory rule set for the period's as-of date (HR-7 reproducibility).
    Map<String, StatutoryRule> resolvedRules = resolveStatutoryRules(asOf);
    boolean usesIllustrative = freezeRuleSet(run, resolvedRules);
    if (usesIllustrative) {
      // Loud runtime signal (HR-9). NO PII in the message.
      log.warn(
          "payroll_run {} period {} computed against ILLUSTRATIVE_PLACEHOLDER statutory figures —"
              + " results are NOT based on verified DJP/BPJS rates",
          run.getId(),
          run.getPeriod());
    }

    List<PayComponent> statutoryComponents = activeStatutoryComponents();
    PayComponent baseComponent = requireComponent("BASE");

    // Track P Phase P7 — the three work-input catalog components are OPTIONAL: a tenant that has
    // not yet activated the ID-2026.2 dataset top-up simply has none of them, and this run must
    // stay byte-identical to a pre-P7 run rather than fail (an operator who never touches
    // attendance/leave/expense-claim reimbursement-via-payroll should see nothing different). When
    // a component IS missing but there is real work to apply, that gap is WARNed loudly (HR-9
    // style)
    // — never silently dropped forever: the linked claim / approved leave / approved overtime stays
    // exactly where it is (unlinked-nothing-lost for leave/overtime; APPROVED+linked for a claim,
    // recoverable by ExpenseClaimPayrollLinker's E5-transitional release path) for a LATER re-run
    // once the dataset is seeded.
    Optional<PayComponent> overtimeComponent =
        payComponentRepository.findByComponentKey(COMPONENT_KEY_OVERTIME);
    Optional<PayComponent> unpaidLeaveComponent =
        payComponentRepository.findByComponentKey(COMPONENT_KEY_UNPAID_LEAVE);
    Optional<PayComponent> reimbursementComponent =
        payComponentRepository.findByComponentKey(
            ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT);
    // P7 review W2 — a catalog integrity check, at rule-resolution/freeze time, regardless of
    // whether this run actually has a reimbursement to apply: a taxable=true misconfiguration must
    // fail LOUDLY, never silently tax the reimbursement (which would corrupt the NET_WAGES_PAYABLE
    // split's identity — ADR 0032 §P7 addendum assumes it never enters the PPh21/BPJS base).
    reimbursementComponent.ifPresent(this::requireReimbursementComponentNonTaxable);
    warnIfWorkInputComponentMissing(
        run, COMPONENT_KEY_OVERTIME, overtimeComponent, !overtimeByEmployee.isEmpty());
    warnIfWorkInputComponentMissing(
        run, COMPONENT_KEY_UNPAID_LEAVE, unpaidLeaveComponent, !unpaidByEmployee.isEmpty());
    warnIfWorkInputComponentMissing(
        run,
        ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT,
        reimbursementComponent,
        !reimbursementByEmployee.isEmpty());

    // December / final-month Art-17 true-up (Track P phase P3): wire ONLY when (a) this run's
    // period is December AND (b) the frozen rule set actually resolves an ANNUAL_PROGRESSIVE rule
    // (PPH21_ARTICLE17) — a tenant still on the illustrative-only catalog (no such rule) simply
    // stays on its ordinary monthly branch, byte-identical to a pre-P3 run. When both hold, the
    // monthly income-tax component (PPH21, normally TER_TABLE/PROGRESSIVE_BRACKET) is swapped
    // in-memory onto the annual rule for every person in this run — never persisted, the catalog
    // itself is untouched (see PayComponent#asAnnualTrueUpVariant).
    boolean isFinalMonth = YearMonth.parse(command.period()).getMonthValue() == 12;
    String annualTrueUpRuleKey = isFinalMonth ? annualProgressiveRuleKey(resolvedRules) : null;
    Map<String, PayComponent> catalogByKey = null;
    if (annualTrueUpRuleKey != null) {
      statutoryComponents =
          swapForAnnualTrueUp(run, statutoryComponents, resolvedRules, annualTrueUpRuleKey);
      catalogByKey = catalogByComponentKey();
      warnIfAnyRuleEffectiveFromFallsInsideFiscalYear(run, resolvedRules, command.period());
      log.info(
          "payroll_run {} period {} is the December/final-month Art-17 true-up — resolved rule"
              + " {}",
          run.getId(),
          run.getPeriod(),
          annualTrueUpRuleKey);
    }

    Money zero = Money.ofMinor(0L, baseCurrency);
    Money grossTotal = zero;
    Money employeeDeductionTotal = zero;
    Money employerContributionTotal = zero;
    Money netTotal = zero;
    // The portion of grossTotal that is EXPENSE_REIMBURSEMENT (Track P Phase P7) — excluded from
    // employer labor cost / allocation (the expense was already recognized at claim approval;
    // booking it again here would double-count it). Tracked separately so grossTotal itself stays
    // the FULL cash-earnings figure (correct for payslip/net-pay display), while the labor-cost
    // exact-sum guard below subtracts it out.
    Money reimbursementAppliedTotal = zero;

    List<AllocatedRow> allAllocations = new ArrayList<>();
    ObjectNode workInputsFrozen = JsonNodeFactory.instance.objectNode();

    for (UUID employeeId : command.employeeIds()) {
      Employee employee =
          employeeRepository
              .findById(employeeId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Unknown employee in this tenant for payroll run"));

      AnnualContext annualContext =
          annualTrueUpRuleKey != null
              ? buildAnnualContext(
                  employee.getId(), command.period(), baseCurrency, resolvedRules, catalogByKey)
              : null;
      PersonInput personInput =
          resolvePersonInput(
              employee,
              baseComponent,
              statutoryComponents,
              resolvedRules,
              asOf,
              baseCurrency,
              annualContext);

      // Track P Phase P7 — append the work-input earnings (unpaid leave / overtime / expense-claim
      // reimbursement) this employee actually has this period, each gated on its catalog component
      // existing (see the warnIfWorkInputComponentMissing call above).
      WorkInputAppendResult appended =
          appendWorkInputs(
              run,
              employee.getId(),
              personInput,
              calendar,
              unpaidByEmployee.getOrDefault(employeeId, List.of()),
              overtimeByEmployee.getOrDefault(employeeId, List.of()),
              reimbursementByEmployee.get(employeeId),
              overtimeComponent,
              unpaidLeaveComponent,
              reimbursementComponent,
              resolvedRules);
      personInput = appended.personInput();
      reimbursementAppliedTotal = reimbursementAppliedTotal.plus(appended.reimbursementApplied());
      if (appended.hasAnyWorkInput()) {
        workInputsFrozen.set(employeeId.toString(), appended.toJsonNode());
      }

      PersonResult result = calculator.compute(personInput);

      persistPayslipLines(run, employee.getId(), result, tenant);

      grossTotal = grossTotal.plus(result.grossEarnings());
      employeeDeductionTotal = employeeDeductionTotal.plus(result.employeeDeductions());
      employerContributionTotal = employerContributionTotal.plus(result.employerContributions());
      netTotal = netTotal.plus(result.net());

      // Allocation (aggregate-then-allocate). Resolves outlets, enforces same legal employer.
      // EXPENSE_REIMBURSEMENT lines are EXCLUDED (allocateForPerson filters them out) — the expense
      // was already recognized at claim approval; it is not a NEW labor cost this run creates.
      List<AllocatedRow> rows =
          allocateForPerson(employee.getId(), result, personInput, asOf, baseCurrency);
      for (AllocatedRow row : rows) {
        LaborCostAllocation entity =
            new LaborCostAllocation(
                run.getId(),
                row.employeeId(),
                row.outletOrgUnitId(),
                row.legalEmployerId(),
                row.glAccount(),
                row.amount(),
                row.earningsShareBp());
        entity.setCompanyId(tenant);
        allocationRepository.save(entity);
        allAllocations.add(row);
      }
    }

    // Exact-sum guard across the whole run: sum(allocations) == total employer labor cost, which
    // EXCLUDES any applied EXPENSE_REIMBURSEMENT (Track P Phase P7 — it is not labor cost).
    Money totalLaborCost =
        grossTotal.minus(reimbursementAppliedTotal).plus(employerContributionTotal);
    assertAllocationSumsToTotal(run, allAllocations, totalLaborCost, baseCurrency);

    run.setTotals(grossTotal, employeeDeductionTotal, employerContributionTotal, netTotal);
    run.recordWorkInputs(workInputsFrozen.toString());
    run.markCalculated();
    return runRepository.save(run);
  }

  /**
   * Persists a FAILED audit row for a calculate attempt that threw, in a SEPARATE new transaction
   * so it survives the rollback of the doomed compute transaction (which left NO trace of the
   * attempt). Best-effort: a failure to write the audit row must not mask the ORIGINAL failure, so
   * it is swallowed (logged, no PII). The compute tx rolled back, so the {@code (company_id,
   * period, run_seq)} slot is free; this row claims a fresh seq. Must be invoked through the Spring
   * proxy (from {@link PayrollRunService}) so its {@code REQUIRES_NEW} engages.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailedAttempt(String period, String baseCurrency) {
    try {
      String tenant = TenantContext.require().companyId();
      int runSeq = nextRunSeq(period);
      PayrollRun failed = new PayrollRun(period, runSeq, baseCurrency);
      failed.setCompanyId(tenant);
      failed.markFailed();
      runRepository.save(failed);
      log.warn("payroll_run {} period {} recorded as FAILED (calculate aborted)", runSeq, period);
    } catch (RuntimeException auditFailure) {
      // Never let the audit write mask the real cause.
      log.warn(
          "could not record a FAILED audit row for period {}: {}", period, auditFailure.toString());
    }
  }

  /**
   * Posts a CALCULATED run: flips it to POSTED and emits {@code PayrollPosted} + aggregated {@code
   * LaborCostAllocated} + {@code PayrollLiabilitiesPosted} (ADR 0032, Track P phase P4, emitted
   * THIRD) via the outbox — atomically (rule 3). Only a CALCULATED -&gt; POSTED transition emits,
   * so a retried post cannot double-emit.
   *
   * @throws IllegalStateException if the run is not in CALCULATED state, or if the freshly
   *     decrypt-and-summed liability buckets do not balance against the run's stored totals (never
   *     emits an unbalanced {@code PayrollLiabilitiesPosted})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayrollRun post(UUID runId) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);
    PayrollRun run =
        runRepository
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown payroll run"));
    if (run.getStatus() != RunStatus.CALCULATED) {
      throw new IllegalStateException(
          "Payroll run " + runId + " is " + run.getStatus() + "; only CALCULATED runs may post");
    }

    run.markPosted(clock.instant());
    runRepository.save(run);
    runRepository.flush();

    // PayrollPosted: company totals + frozen rule versions + illustrative flag. NO PII.
    List<PayrollPostedSchema.RuleVersion> ruleVersions = readFrozenRuleVersions(run);
    outboxWriter.write(
        PayrollPostedSchema.AGGREGATE_TYPE,
        run.getId().toString(),
        PayrollPostedSchema.EVENT_TYPE,
        AvroSerde.serialize(PayrollPostedSchema.toRecord(run, ruleVersions)),
        null,
        companyId,
        clock.instant());

    // LaborCostAllocated: one event PER (outlet, gl_account) bucket, aggregated across employees.
    Map<OutletGl, Money> buckets = aggregateBuckets(run);
    for (Map.Entry<OutletGl, Money> bucket : buckets.entrySet()) {
      boolean unallocated = UNALLOCATED_OUTLET.equals(bucket.getKey().outletId());
      outboxWriter.write(
          LaborCostAllocatedSchema.AGGREGATE_TYPE,
          run.getId().toString(),
          LaborCostAllocatedSchema.EVENT_TYPE,
          AvroSerde.serialize(
              LaborCostAllocatedSchema.toRecord(
                  run.getId(),
                  tenant,
                  run.getPeriod(),
                  bucket.getKey().outletId(),
                  bucket.getKey().glAccount(),
                  bucket.getValue(),
                  run.getRunSeq(),
                  run.usesIllustrativeRules(),
                  unallocated,
                  clock.instant())),
          null,
          companyId,
          clock.instant());
    }

    // PayrollLiabilitiesPosted (ADR 0032, Track P phase P4): emitted THIRD, after PayrollPosted
    // and every LaborCostAllocated bucket, in the SAME outbox transaction. employerCostTotal is
    // the SAME sum LaborCostAllocated's buckets total to (gross + employer contributions) MINUS any
    // applied EXPENSE_REIMBURSEMENT (Track P Phase P7, ADR 0032/0030 addenda — reimbursement is not
    // labor cost) — the Dr leg finance's liability writer books against. The identity is asserted
    // BEFORE writing: an unbalanced set is never emitted (HR-3).
    LiabilityComputation liabilityComputation = computeLiabilityBuckets(run);
    Money employerCostTotal =
        run.getGrossTotal()
            .minus(liabilityComputation.reimbursementTotal())
            .plus(run.getEmployerContributionTotal());
    assertLiabilityIdentity(run, employerCostTotal, liabilityComputation.buckets());
    outboxWriter.write(
        PayrollLiabilitiesPostedSchema.AGGREGATE_TYPE,
        run.getId().toString(),
        PayrollLiabilitiesPostedSchema.EVENT_TYPE,
        AvroSerde.serialize(
            PayrollLiabilitiesPostedSchema.toRecord(
                run, employerCostTotal, liabilityComputation.buckets())),
        null,
        companyId,
        clock.instant());

    // ADR 0030 §6 (Track E phase E5, now LIVE via Track P Phase P7) — the payroll<->expense-claim
    // seam, joined to THIS SAME CALCULATED->POSTED transaction (ExpenseClaimPayrollLinker is
    // propagation MANDATORY): flips every claim linked to this run from APPROVED to REIMBURSED and
    // emits one ExpenseReimbursementSettled(PAYROLL) per claim — the run's exactly-once posting
    // discipline extends to these emits. Its internal gate (see the linker's class Javadoc) stays a
    // safe no-op (returns 0, flips nothing) whenever the run carries NO EXPENSE_REIMBURSEMENT
    // payslip line — either no claim was linked, or the dataset carrying that component was not
    // active when calculate() ran — never settling a payable for money the employee did not
    // actually receive via this run.
    expenseClaimPayrollLinker.markReimbursedAndEmit(run);

    return run;
  }

  // ---------------------------------------------------------------------
  // Liabilities (Track P phase P4, ADR 0032)
  // ---------------------------------------------------------------------

  /** The five liability roles {@code PayrollLiabilitiesPosted}'s buckets carry (ADR 0032). */
  private static final String ROLE_NET_WAGES = "NET_WAGES_PAYABLE";

  private static final String ROLE_PPH21 = "PPH21_PAYABLE";
  private static final String ROLE_BPJS_KES = "BPJS_KES_PAYABLE";
  private static final String ROLE_BPJS_TK = "BPJS_TK_PAYABLE";
  private static final String ROLE_OTHER = "OTHER_DEDUCTIONS_PAYABLE";

  /** {@code component_key}s carrying BPJS Kesehatan (both the EE and ER legs). */
  private static final Set<String> BPJS_KES_COMPONENT_KEYS = Set.of("BPJS_KES_EE", "BPJS_KES_ER");

  /** {@code component_key}s carrying BPJS Ketenagakerjaan — JHT + JP (EE and ER), JKK/JKM (ER). */
  private static final Set<String> BPJS_TK_COMPONENT_KEYS =
      Set.of("JHT_EE", "JHT_ER", "JP_EE", "JP_ER", "JKK_ER", "JKM_ER");

  /**
   * The result of {@link #computeLiabilityBuckets}: the five (zero-omitted) buckets AND the run's
   * total applied {@code EXPENSE_REIMBURSEMENT} (Track P Phase P7) — the caller ({@link #post})
   * needs the latter to also strip reimbursement out of {@code employerCostTotal}, so both are
   * derived from the SAME single decrypt-and-scan pass over the run's payslip lines.
   */
  private record LiabilityComputation(
      List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets, Money reimbursementTotal) {}

  /**
   * Computes the run's liability buckets by decrypting and summing every {@link PayslipLine} across
   * every employee of the run (ADR 0032; Track P Phase P7 addendum below): {@code PPH21} lines →
   * PPH21_PAYABLE; {@link #BPJS_KES_COMPONENT_KEYS} → BPJS_KES_PAYABLE (EE withheld + ER
   * contribution together); {@link #BPJS_TK_COMPONENT_KEYS} → BPJS_TK_PAYABLE (EE withheld + ER
   * contribution together); any OTHER deduction line — EMPLOYEE or EMPLOYER bearer alike, e.g. a
   * future custom component such as a loan repayment — → the catch-all OTHER_DEDUCTIONS_PAYABLE.
   * Bucketing EVERY deduction line regardless of bearer (not just EMPLOYEE-borne ones) is what
   * makes {@link #assertLiabilityIdentity} hold structurally for ANY future custom component:
   * {@code net_total + Σ(the other 4 buckets) = (gross - employeeDeductions) + (employeeDeductions
   * + employerContributions) = gross + employerContributions = employerCostTotal} (the pre-P7
   * identity). A zero-amount bucket is OMITTED from the returned list (mirrors {@code
   * TaxFilingWriter}'s zero-leg omission); the December Art-17 true-up (Track P phase P3) can drive
   * {@code PPH21_PAYABLE} negative (a refund month) — finance posts a negative bucket as the
   * opposite journal side (ADR 0032).
   *
   * <p><strong>Track P Phase P7 — the NET_WAGES_PAYABLE / reimbursement split (ADR 0032 §P7
   * addendum, ADR 0030 §10).</strong> An {@code EXPENSE_REIMBURSEMENT} EARNING line lifts the
   * employee's NET pay (it is real cash the employee receives via this run's transfer) but is NOT
   * labor cost — the claim's expense was already recognized (Dr expense / Cr {@code 2600 Employee
   * Expense Payable}) at manager APPROVAL time (ADR 0030). Crediting the FULL {@code net_total} to
   * {@code NET_WAGES_PAYABLE (2640)} here would DOUBLE-BOOK that portion: once as the {@code 2600}
   * payable (settled separately by {@code ExpenseClaimPayrollLinker#markReimbursedAndEmit}'s {@code
   * ExpenseReimbursementSettled(PAYROLL)} event, which finance's {@code empexpense} consumer clears
   * Dr 2600 / Cr CASH_CLEARING) and again as {@code 2640}. So {@code NET_WAGES_PAYABLE = net_total
   * - reimbursementTotal} — the labor-only portion of net pay finance owes via the payroll
   * liability account; the reimbursement portion is owed (and settled) via the SEPARATE {@code
   * 2600} payable instead. The identity still holds on the LABOR-ONLY amounts (see {@link #post}'s
   * {@code employerCostTotal}, which subtracts the SAME {@code reimbursementTotal}) — {@code
   * employerCostTotal = employerCost_labor_only = net_total - reimbursementTotal + Σ(other 4
   * buckets)}. The PAYSLIP itself, and the net-pay BANK FILE, still show/pay the FULL net including
   * the reimbursement (the employee receives ONE transfer) — only the GL split differs.
   */
  private LiabilityComputation computeLiabilityBuckets(PayrollRun run) {
    String baseCurrency = run.getBaseCurrency();
    Money zero = Money.ofMinor(0L, baseCurrency);
    Money pph21 = zero;
    Money bpjsKes = zero;
    Money bpjsTk = zero;
    Money other = zero;
    Money reimbursement = zero;

    for (PayslipLine line : payslipLineRepository.findByPayrollRunId(run.getId())) {
      String componentKey = line.getComponentKey();
      Money amount = line.getAmount();
      if (line.getKind() == PayComponentKind.EARNING) {
        if (ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT.equals(componentKey)) {
          reimbursement = reimbursement.plus(amount);
        }
        continue;
      }
      if ("PPH21".equals(componentKey)) {
        pph21 = pph21.plus(amount);
      } else if (BPJS_KES_COMPONENT_KEYS.contains(componentKey)) {
        bpjsKes = bpjsKes.plus(amount);
      } else if (BPJS_TK_COMPONENT_KEYS.contains(componentKey)) {
        bpjsTk = bpjsTk.plus(amount);
      } else {
        other = other.plus(amount);
      }
    }

    Money netWagesPayable = run.getNetTotal().minus(reimbursement);
    List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets = new ArrayList<>(5);
    addBucketIfNonZero(buckets, ROLE_NET_WAGES, netWagesPayable);
    addBucketIfNonZero(buckets, ROLE_PPH21, pph21);
    addBucketIfNonZero(buckets, ROLE_BPJS_KES, bpjsKes);
    addBucketIfNonZero(buckets, ROLE_BPJS_TK, bpjsTk);
    addBucketIfNonZero(buckets, ROLE_OTHER, other);
    return new LiabilityComputation(buckets, reimbursement);
  }

  private void addBucketIfNonZero(
      List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets, String role, Money amount) {
    if (!amount.isZero()) {
      buckets.add(new PayrollLiabilitiesPostedSchema.LiabilityBucket(role, amount));
    }
  }

  /**
   * Asserts the accounting identity BEFORE any {@code PayrollLiabilitiesPosted} event is written:
   * {@code employerCostTotal == Σ(bucket amounts)} — {@code buckets} already INCLUDES {@code
   * NET_WAGES_PAYABLE} (== {@code run.getNetTotal()}, see {@link #computeLiabilityBuckets}), so it
   * must NOT be added again here (a prior version of this method double-counted it — summing {@code
   * run.getNetTotal()} AND iterating {@code buckets}, whose first entry already carries that same
   * net total — inflating the check by exactly one extra net total; caught by the P4 test suite,
   * never shipped). This is still a genuine cross-check, not a restatement of the same arithmetic:
   * {@code employerCostTotal} comes from {@link PayrollRun}'s STORED {@code gross_total}/{@code
   * employer_contribution_total} (accumulated by {@link #calculate}); {@code NET_WAGES_PAYABLE} is
   * the STORED {@code net_total}; the other buckets are FRESHLY decrypted-and-summed from {@code
   * payslip_line} rows in {@link #computeLiabilityBuckets} — independently derived from the same
   * underlying data, so a mismatch signals a genuine bug (e.g. a component miscategorised EARNING
   * vs DEDUCTION) rather than a tautology. Never emits an unbalanced event — throws loudly instead
   * (HR-3: money is never silently misreported).
   */
  private void assertLiabilityIdentity(
      PayrollRun run,
      Money employerCostTotal,
      List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets) {
    Money sum = Money.ofMinor(0L, run.getBaseCurrency());
    for (PayrollLiabilitiesPostedSchema.LiabilityBucket bucket : buckets) {
      sum = sum.plus(bucket.amount());
    }
    if (!sum.equals(employerCostTotal)) {
      throw new IllegalStateException(
          "payroll_run "
              + run.getId()
              + " period "
              + run.getPeriod()
              + " liability identity violated: Σ(buckets) = "
              + sum.amountMinor()
              + " != employer_cost_total = "
              + employerCostTotal.amountMinor()
              + " — refusing to emit an unbalanced PayrollLiabilitiesPosted event");
    }
  }

  // ---------------------------------------------------------------------
  // Completeness gate
  // ---------------------------------------------------------------------

  /**
   * Gates the run on completeness (every expected source must have sealed the period). Returns
   * {@code true} if the run is UNGATED (no expected sources supplied) so the caller can WARN with
   * the run id.
   */
  private boolean enforceCompleteness(RunPayrollCommand command) {
    List<UUID> expected = command.expectedSourceBusinessIds();
    if (expected == null || expected.isEmpty()) {
      return true; // ungated: no expected sources declared — the caller WARNs loudly
    }
    List<UUID> unsealed = new ArrayList<>();
    for (UUID businessId : expected) {
      if (periodSealRepository.findByBusinessIdAndPeriod(businessId, command.period()).isEmpty()) {
        unsealed.add(businessId);
      }
    }
    if (!unsealed.isEmpty()) {
      throw new IncompletePeriodException(command.period(), unsealed);
    }
    return false;
  }

  private void recordSealedLedger(PayrollRun run, String period) {
    ArrayNode ledger = JsonNodeFactory.instance.arrayNode();
    periodSealRepository
        .findByPeriod(period)
        .forEach(seal -> ledger.add(seal.getBusinessId().toString()));
    run.recordSealedSources(ledger.toString());
  }

  // ---------------------------------------------------------------------
  // Work inputs (Track P Phase P7) — pending gate, resolution, and per-employee synthesis
  // ---------------------------------------------------------------------

  /**
   * Chunks {@code ids} at {@link #CHUNK_SIZE} and throws {@link PendingWorkEntriesException} if any
   * SUBMITTED (undecided) leave request or overtime entry exists for {@code period} among them —
   * see the class Javadoc's calculate() note: this runs FIRST, before any other work.
   */
  private void requireNoPendingWorkEntries(List<UUID> employeeIds, String period) {
    List<UUID> pendingLeave = new ArrayList<>();
    List<UUID> pendingOvertime = new ArrayList<>();
    for (List<UUID> idChunk : chunk(employeeIds)) {
      pendingLeave.addAll(leaveRequestRepository.findSubmittedIdsForPeriod(idChunk, period));
      pendingOvertime.addAll(overtimeEntryRepository.findSubmittedIdsForPeriod(idChunk, period));
    }
    if (!pendingLeave.isEmpty() || !pendingOvertime.isEmpty()) {
      throw new PendingWorkEntriesException(period, pendingLeave, pendingOvertime);
    }
  }

  /**
   * The result of {@link #reimbursementInfoByEmployee}: one employee's linked (still-APPROVED)
   * expense-claim reimbursement — the aggregate total AND the individual claim ids that make it up
   * (P7 review W3 — {@code work_inputs_json} freezes the ids, not just the total/count, for full
   * reproducibility).
   */
  private record ReimbursementInfo(Money total, long claimCount, List<UUID> claimIds) {}

  /**
   * The linked (still-APPROVED) expense-claim reimbursement info per employee for {@code runId} —
   * combines {@link ExpenseClaimPayrollLinker#findLinkedClaimTotalsByEmployee} (the aggregate) with
   * {@link ExpenseClaimPayrollLinker#findLinkedClaimIdsByEmployee} (the individual claim ids, P7
   * review W3), keeping only rows in the run's OWN {@code baseCurrency} (v1 claims are
   * single-currency per tenant, ADR 0030 §9 — a row in a different currency is a data anomaly this
   * run cannot safely apply and is logged + skipped rather than silently mis-added or crashing the
   * run; see {@code ExpenseClaimPayrollLinker#markReimbursedAndEmit}'s W4 fix for how a skipped
   * employee's claim stays APPROVED+linked rather than being settled for money never received).
   */
  private Map<UUID, ReimbursementInfo> reimbursementInfoByEmployee(
      UUID runId, String baseCurrency) {
    Map<UUID, List<UUID>> claimIdsByEmployee = new LinkedHashMap<>();
    for (LinkedClaimIdView idView : expenseClaimPayrollLinker.findLinkedClaimIdsByEmployee(runId)) {
      if (!baseCurrency.equals(idView.getCurrency())) {
        continue; // symmetric with the total-currency filter below — never mix currencies (rule 8)
      }
      claimIdsByEmployee
          .computeIfAbsent(idView.getEmployeeId(), k -> new ArrayList<>())
          .add(idView.getClaimId());
    }

    Map<UUID, ReimbursementInfo> infoByEmployee = new LinkedHashMap<>();
    for (LinkedClaimTotalView view :
        expenseClaimPayrollLinker.findLinkedClaimTotalsByEmployee(runId)) {
      if (!baseCurrency.equals(view.getCurrency())) {
        log.warn(
            "payroll_run {} employee {} has a linked expense-claim total in currency {} but the"
                + " run's base currency is {} — skipped (v1 is single-currency, ADR 0030 §9)",
            runId,
            view.getEmployeeId(),
            view.getCurrency(),
            baseCurrency);
        continue;
      }
      List<UUID> claimIds = claimIdsByEmployee.getOrDefault(view.getEmployeeId(), List.of());
      infoByEmployee.put(
          view.getEmployeeId(),
          new ReimbursementInfo(
              Money.ofMinor(view.getTotalMinor(), baseCurrency), view.getClaimCount(), claimIds));
    }
    return infoByEmployee;
  }

  /** Every APPROVED {@code UNPAID} leave request for {@code period}, grouped by employee. */
  private Map<UUID, List<ApprovedUnpaidLeaveView>> approvedUnpaidLeaveByEmployee(
      List<UUID> employeeIds, String period) {
    Map<UUID, List<ApprovedUnpaidLeaveView>> byEmployee = new LinkedHashMap<>();
    for (List<UUID> idChunk : chunk(employeeIds)) {
      for (ApprovedUnpaidLeaveView view :
          leaveRequestRepository.findApprovedUnpaidForPeriod(idChunk, period)) {
        byEmployee.computeIfAbsent(view.getEmployeeId(), k -> new ArrayList<>()).add(view);
      }
    }
    return byEmployee;
  }

  /** Every APPROVED overtime entry for {@code period}, grouped by employee. */
  private Map<UUID, List<ApprovedOvertimeView>> approvedOvertimeByEmployee(
      List<UUID> employeeIds, String period) {
    Map<UUID, List<ApprovedOvertimeView>> byEmployee = new LinkedHashMap<>();
    for (List<UUID> idChunk : chunk(employeeIds)) {
      for (ApprovedOvertimeView view :
          overtimeEntryRepository.findApprovedForPeriod(idChunk, period)) {
        byEmployee.computeIfAbsent(view.getEmployeeId(), k -> new ArrayList<>()).add(view);
      }
    }
    return byEmployee;
  }

  /**
   * Logs ONE loud WARN (HR-9 style, no PII) when {@code componentKey}'s catalog component is
   * missing but this run has real work of that kind to apply — the tenant has approved leave /
   * overtime / linked a claim but has not yet activated the dataset that carries the earning
   * component, so the run completes WITHOUT applying it rather than fail or guess. See the {@code
   * calculate()} call-site comment for the full rationale.
   */
  private void warnIfWorkInputComponentMissing(
      PayrollRun run, String componentKey, Optional<PayComponent> component, boolean hasWork) {
    if (hasWork && component.isEmpty()) {
      log.warn(
          "payroll_run {} period {} has approved work input(s) for component '{}' but no such"
              + " pay_component is seeded — SKIPPING it this run (activate the ID-2026.2 dataset"
              + " top-up, or an equivalent component, then re-run)",
          run.getId(),
          run.getPeriod(),
          componentKey);
    }
  }

  /**
   * Fail-loud carry-in (P7 review W2): the EXPENSE_REIMBURSEMENT catalog component MUST be
   * non-taxable — see {@link TaxableReimbursementComponentException}'s Javadoc for the full
   * rationale. Checked at rule-resolution/freeze time, once per run, regardless of whether there is
   * an actual reimbursement to apply this period.
   */
  private void requireReimbursementComponentNonTaxable(PayComponent component) {
    if (component.isTaxable()) {
      throw new TaxableReimbursementComponentException(component.getComponentKey());
    }
  }

  /**
   * The result of {@link #appendWorkInputs}: the person input WITH the work-input earnings
   * appended, the reimbursement amount actually applied (zero if none/component missing — the
   * caller accumulates this to exclude it from labor cost), and the frozen per-employee JSON node
   * for {@code payroll_run.work_inputs_json} (Track P Phase P7 reproducibility).
   */
  private record WorkInputAppendResult(
      PersonInput personInput,
      Money reimbursementApplied,
      ObjectNode jsonNode,
      boolean hasAnyWorkInput) {

    ObjectNode toJsonNode() {
      return jsonNode;
    }
  }

  /**
   * Synthesizes the UNPAID_LEAVE / OVERTIME / EXPENSE_REIMBURSEMENT {@link EarningInput}s for one
   * employee (each independently gated on its catalog component actually existing) and appends them
   * to {@code base}'s earnings — returning a NEW {@link PersonInput} (records are immutable) plus
   * the frozen work-input breakdown. UNPAID_LEAVE is a SIGNED-NEGATIVE, TAXABLE earning, CLAMPED at
   * the work-calendar divisor so labor pay floors at zero (P7 review W1 — shrinks the PPh21/BPJS
   * tax bases, per the component's catalog {@code taxable=true}); OVERTIME is a positive TAXABLE
   * earning, summed from PER-CALENDAR-DAY tier walks (P7 review C1 — PP 35/2021 tiers reset per
   * day, never aggregated across a whole month); EXPENSE_REIMBURSEMENT is a positive NON-taxable
   * earning (lifts {@code grossEarnings}/net but never the tax/BPJS base — the component's catalog
   * {@code taxable=false} already makes {@link GrossToNetCalculator} exclude it from {@code
   * taxableCashEarnings}; {@link #requireReimbursementComponentNonTaxable} asserts this holds, P7
   * review W2).
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  private WorkInputAppendResult appendWorkInputs(
      PayrollRun run,
      UUID employeeId,
      PersonInput base,
      WorkCalendar calendar,
      List<ApprovedUnpaidLeaveView> unpaidLeaves,
      List<ApprovedOvertimeView> overtimeEntries,
      ReimbursementInfo reimbursementInfo,
      Optional<PayComponent> overtimeComponent,
      Optional<PayComponent> unpaidLeaveComponent,
      Optional<PayComponent> reimbursementComponent,
      Map<String, StatutoryRule> resolvedRules) {
    List<EarningInput> earnings = new ArrayList<>(base.earnings());
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    boolean hasAnyWorkInput = false;
    Money reimbursementApplied = Money.ofMinor(0L, base.basePay().currency());

    // ---- UNPAID_LEAVE ----------------------------------------------------
    int unpaidDays = unpaidLeaves.stream().mapToInt(ApprovedUnpaidLeaveView::getDays).sum();
    if (unpaidDays > 0 && unpaidLeaveComponent.isPresent()) {
      int clampedDays =
          workInputCalculator.clampUnpaidDays(unpaidDays, calendar.getMonthlyDivisor());
      if (clampedDays < unpaidDays) {
        // P7 review W1: unpaid days beyond a full month can never deduct further — labor pay
        // floors at zero, never goes negative. Loud WARN (HR-9), no PII beyond ids already public
        // within the tenant.
        log.warn(
            "payroll_run {} employee {} has {} approved unpaid day(s) this period, exceeding the"
                + " work calendar's monthly divisor {} — clamped to {} (a full month's base pay;"
                + " unpaid leave can never drive net below the labor floor)",
            run.getId(),
            employeeId,
            unpaidDays,
            calendar.getMonthlyDivisor(),
            clampedDays);
      }
      Money amount =
          workInputCalculator.unpaidLeaveEarning(
              base.basePay(), unpaidDays, calendar.getMonthlyDivisor());
      earnings.add(new EarningInput(unpaidLeaveComponent.get(), amount));
      ObjectNode leaveNode = node.putObject("unpaidLeave");
      leaveNode.put("days", unpaidDays);
      if (clampedDays < unpaidDays) {
        leaveNode.put("appliedDays", clampedDays);
      }
      ArrayNode ids = leaveNode.putArray("requestIds");
      unpaidLeaves.forEach(v -> ids.add(v.getId().toString()));
      hasAnyWorkInput = true;
    }

    // ---- OVERTIME ------------------------------------------------------------
    // P7 review C1 (CRITICAL): PP 35/2021's multiplier tiers reset PER CALENDAR DAY. Group
    // approved entries by (work_date, day_kind) and apply ONE independent tier walk per day,
    // summing the results — walking the tiers ONCE over the whole month's aggregated minutes
    // would grant the cheap first-tier rate only once for the entire month, overpaying by
    // 11-44% in the review's worked examples (5 weekday days x 2h = 17.5x hourly per-day-correct,
    // vs 19.5x wrongly aggregated; 2 rest days x 8h = 34x per-day-correct, vs 49x wrongly
    // aggregated).
    Map<LocalDate, Integer> weekdayMinutesByDate = new LinkedHashMap<>();
    Map<LocalDate, Integer> restDayMinutesByDate = new LinkedHashMap<>();
    for (ApprovedOvertimeView v : overtimeEntries) {
      if ("WEEKDAY".equals(v.getDayKind())) {
        weekdayMinutesByDate.merge(v.getWorkDate(), v.getMinutes(), Integer::sum);
      } else {
        restDayMinutesByDate.merge(v.getWorkDate(), v.getMinutes(), Integer::sum);
      }
    }
    int totalWeekdayMinutes =
        weekdayMinutesByDate.values().stream().mapToInt(Integer::intValue).sum();
    int totalRestDayMinutes =
        restDayMinutesByDate.values().stream().mapToInt(Integer::intValue).sum();
    if ((totalWeekdayMinutes > 0 || totalRestDayMinutes > 0) && overtimeComponent.isPresent()) {
      StatutoryRule overtimeRule = resolvedRules.get(overtimeComponent.get().getStatutoryRuleKey());
      if (overtimeRule == null) {
        log.warn(
            "employee {} has approved overtime this period but the OVERTIME component's statutory"
                + " rule is not currently resolved (effective-date gap?) — skipping overtime this"
                + " run",
            employeeId);
      } else {
        StatutoryParams.HourlyRateTableParams params =
            StatutoryParams.hourlyRateTable(overtimeRule.getParamsJson());
        Money amount = Money.ofMinor(0L, base.basePay().currency());
        for (int minutes : weekdayMinutesByDate.values()) {
          amount =
              amount.plus(workInputCalculator.overtimeEarning(base.basePay(), params, minutes, 0));
        }
        for (int minutes : restDayMinutesByDate.values()) {
          amount =
              amount.plus(workInputCalculator.overtimeEarning(base.basePay(), params, 0, minutes));
        }
        earnings.add(new EarningInput(overtimeComponent.get(), amount));
        ObjectNode overtimeNode = node.putObject("overtime");
        overtimeNode.put("weekdayMinutes", totalWeekdayMinutes);
        overtimeNode.put("restDayMinutes", totalRestDayMinutes);
        ArrayNode ids = overtimeNode.putArray("entryIds");
        overtimeEntries.forEach(v -> ids.add(v.getId().toString()));
        hasAnyWorkInput = true;
      }
    }

    // ---- EXPENSE_REIMBURSEMENT ---------------------------------------------
    if (reimbursementInfo != null
        && reimbursementInfo.total().isPositive()
        && reimbursementComponent.isPresent()) {
      Money reimbursementTotal = reimbursementInfo.total();
      earnings.add(new EarningInput(reimbursementComponent.get(), reimbursementTotal));
      ObjectNode reimbursementNode = node.putObject("reimbursement");
      reimbursementNode.put("amountMinor", reimbursementTotal.amountMinor());
      reimbursementNode.put("currency", reimbursementTotal.currency().getCurrencyCode());
      reimbursementNode.put("claimCount", reimbursementInfo.claimCount());
      ArrayNode claimIds = reimbursementNode.putArray("claimIds");
      reimbursementInfo.claimIds().forEach(id -> claimIds.add(id.toString()));
      reimbursementApplied = reimbursementTotal;
      hasAnyWorkInput = true;
    }

    PersonInput extended =
        new PersonInput(
            base.employeeId(),
            base.ptkpStatus(),
            base.baseComponent(),
            base.basePay(),
            List.copyOf(earnings),
            base.statutoryComponents(),
            base.otherDeductions(),
            base.resolvedRules(),
            base.hasNpwp(),
            base.annualContext());
    return new WorkInputAppendResult(extended, reimbursementApplied, node, hasAnyWorkInput);
  }

  /**
   * Chunks {@code ids} at {@link #CHUNK_SIZE} (CLAUDE.md) — the {@code ExpenseClaimPayrollLinker}
   * idiom.
   */
  private List<List<UUID>> chunk(List<UUID> ids) {
    List<List<UUID>> chunks = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
      chunks.add(ids.subList(i, Math.min(i + CHUNK_SIZE, ids.size())));
    }
    return chunks;
  }

  // ---------------------------------------------------------------------
  // Rule freezing (reproducibility + illustrative flag)
  // ---------------------------------------------------------------------

  private Map<String, StatutoryRule> resolveStatutoryRules(LocalDate asOf) {
    Map<String, StatutoryRule> resolved = new LinkedHashMap<>();
    for (StatutoryRule rule :
        statutoryRuleRepository
            .findByActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
                asOf, asOf)) {
      // EXACTLY one effective row per rule_key as-of the run date. Two OFFICIAL rows overlapping in
      // effective range is a misconfiguration that a first-wins putIfAbsent would silently mask
      // (picking one rate arbitrarily). Fail the run LOUDLY instead so the ambiguity is fixed in
      // data, never paid against a silently-chosen rate.
      StatutoryRule previous = resolved.putIfAbsent(rule.getRuleKey(), rule);
      if (previous != null) {
        throw new IllegalStateException(
            "More than one effective statutory_rule for rule_key '"
                + rule.getRuleKey()
                + "' as of "
                + asOf
                + " (ids "
                + previous.getId()
                + " and "
                + rule.getId()
                + "); exactly one effective row is required — fix the overlapping effective ranges");
      }
    }
    return resolved;
  }

  private boolean freezeRuleSet(PayrollRun run, Map<String, StatutoryRule> resolvedRules) {
    ObjectNode frozen = JsonNodeFactory.instance.objectNode();
    boolean usesIllustrative = false;
    for (Map.Entry<String, StatutoryRule> entry : resolvedRules.entrySet()) {
      StatutoryRule rule = entry.getValue();
      ObjectNode node = frozen.putObject(entry.getKey());
      node.put("statutory_rule_id", rule.getId().toString());
      node.put("rule_version", rule.getRuleVersion());
      usesIllustrative |= rule.isIllustrative();
    }
    run.freezeRuleVersionSet(frozen.toString(), usesIllustrative);
    return usesIllustrative;
  }

  private List<PayrollPostedSchema.RuleVersion> readFrozenRuleVersions(PayrollRun run) {
    List<PayrollPostedSchema.RuleVersion> versions = new ArrayList<>();
    try {
      com.fasterxml.jackson.databind.JsonNode root =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.getRuleVersionSetJson());
      root.fields()
          .forEachRemaining(
              e ->
                  versions.add(
                      new PayrollPostedSchema.RuleVersion(
                          e.getKey(), e.getValue().get("rule_version").asText())));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read frozen rule-version set", e);
    }
    return versions;
  }

  // ---------------------------------------------------------------------
  // Per-person input resolution
  // ---------------------------------------------------------------------

  private PersonInput resolvePersonInput(
      Employee employee,
      PayComponent baseComponent,
      List<PayComponent> statutoryComponents,
      Map<String, StatutoryRule> resolvedRules,
      LocalDate asOf,
      String baseCurrency,
      AnnualContext annualContext) {
    Money base = Money.ofMinor(0L, baseCurrency);
    List<EarningInput> earnings = new ArrayList<>();
    List<DeductionInput> deductions = new ArrayList<>();

    List<CompensationPackage> packages = compPackageRepository.findByEmployeeId(employee.getId());
    for (CompensationPackage pkg : packages) {
      if (!pkg.coversAsOf(asOf)) {
        continue;
      }
      // Statutory scope gate (Track P Phase P7): this engine supports MONTHLY compensation only —
      // defensive today (PayFrequency carries only MONTHLY), fails loudly the moment a second
      // cadence is ever added rather than silently misprorate it.
      if (pkg.getPayFrequency() != PayFrequency.MONTHLY) {
        throw new NonMonthlyCompensationException(employee.getId(), pkg.getId());
      }
      Money pkgBase = pkg.getBasePay();
      // No implicit FX: a comp package in a different currency fails the run (finance owns FX).
      pkgBase.requireSameCurrencyAs(base);
      base = base.plus(pkgBase);

      for (EarningRule earningRule :
          earningRuleRepository.findByCompensationPackageId(pkg.getId())) {
        if (!earningRule.coversAsOf(asOf)) {
          continue;
        }
        resolveEarningRule(
            employee, earningRule, pkgBase, baseCurrency, asOf, earnings, deductions);
      }
    }

    return new PersonInput(
        employee.getId(),
        employee.getPtkpStatus(),
        baseComponent,
        base,
        List.copyOf(earnings),
        List.copyOf(statutoryComponents),
        List.copyOf(deductions),
        resolvedRules,
        employee.hasNpwp(),
        // Null on every run except the December/final-month Art-17 true-up (Track P phase P3),
        // where the caller has already decrypt-and-summed this employee's active prior-period
        // payslip lines into the year-to-date figures the ANNUAL_PROGRESSIVE branch needs.
        annualContext);
  }

  private void resolveEarningRule(
      Employee employee,
      EarningRule earningRule,
      Money pkgBase,
      String baseCurrency,
      LocalDate asOf,
      List<EarningInput> earnings,
      List<DeductionInput> deductions) {
    PayComponent component =
        payComponentRepository
            .findById(earningRule.getPayComponentId())
            .orElseThrow(
                () -> new IllegalArgumentException("Earning rule references an unknown component"));
    Money amount =
        switch (earningRule.getParamKind()) {
          case FIXED_AMOUNT -> earningRule.getFixedAmount();
          case PERCENT_OF_BASE -> pkgBase.applyBasisPoints(earningRule.getPercentBasisPoints());
          case PER_METRIC_UNIT -> resolveMetricEarning(employee, earningRule, asOf);
          case PERCENT_OF_METRIC ->
              resolvePercentOfMetricEarning(employee, earningRule, baseCurrency, asOf);
        };
    amount.requireSameCurrencyAs(Money.ofMinor(0L, baseCurrency));
    if (component.getKind() == PayComponentKind.EARNING) {
      earnings.add(new EarningInput(component, amount));
    } else {
      deductions.add(new DeductionInput(component, amount));
    }
  }

  private Money resolveMetricEarning(Employee employee, EarningRule earningRule, LocalDate asOf) {
    Money rate = earningRule.getRate();
    // Run-month prefix ("YYYY-MM"). A metric is stored at whatever grain the producer published —
    // daily ("YYYY-MM-DD") or monthly ("YYYY-MM"). SUM every row for this metric_key + subject
    // whose period FALLS WITHIN the run month (a YYYY-MM prefix match), so a daily-grain commission
    // metric resolves correctly instead of silently matching nothing and resolving to zero.
    String monthPrefix = YearMonth.from(asOf).toString();
    long totalUnits = 0L;
    // Single-grain guard (#34): the prefix match sums every row in the run month REGARDLESS of
    // period grain. A metric is published either at DAILY grain (period "YYYY-MM-DD") or MONTHLY
    // grain (period "YYYY-MM"); the two are alternatives, never both. If a producer ever emitted
    // BOTH a daily AND a monthly row for the same (metric_key, subject, month) — a double-publish
    // at
    // two grains — the prefix sum would DOUBLE-COUNT the same operational activity into the
    // commission. The period grain is the PERIOD-STRING shape (not MetricInput.grain, which is the
    // SUBJECT grain outlet/employee/shift); assert every matched row for a subject shares ONE
    // period
    // grain and fail loudly on a mixed-grain set rather than guess which is canonical.
    PeriodGrain resolvedGrain = null;
    // Attribute the metric to the employee's outlets in the period (subject_id = outlet id).
    for (UUID outletId : outletsForEmployee(employee.getId(), asOf)) {
      List<MetricInput> rows =
          metricInputRepository.findByMetricKeyAndSubjectIdAndPeriodStartingWith(
              earningRule.getMetricKey(), outletId, monthPrefix);
      for (MetricInput row : rows) {
        PeriodGrain rowGrain = periodGrainOf(row.getPeriod());
        if (resolvedGrain == null) {
          resolvedGrain = rowGrain;
        } else if (resolvedGrain != rowGrain) {
          throw new IllegalStateException(
              "Metric '"
                  + earningRule.getMetricKey()
                  + "' for subject "
                  + outletId
                  + " in "
                  + monthPrefix
                  + " has rows at MIXED period grains ("
                  + resolvedGrain
                  + " and "
                  + rowGrain
                  + "); a single (metric_key, subject, month) must resolve at exactly ONE grain —"
                  + " summing both would double-count. Fix the upstream producer's grain");
        }
        totalUnits += row.getValue();
      }
    }
    return rate.multiply(totalUnits);
  }

  /**
   * Own-sales commission: {@code basis_points × summed metric AMOUNT} for the employee's linked
   * login (EMPLOYEE grain — subject = the Keycloak sub the sales were rung under). The metric value
   * is already money in minor units (e.g. {@code sales_amount}), so we sum it and apply the rate.
   * An employee with NO linked login has no own-sales metrics → 0 (never an error — an unlinked HR
   * record simply earns no commission). Reuses the single-period-grain guard.
   */
  private Money resolvePercentOfMetricEarning(
      Employee employee, EarningRule earningRule, String baseCurrency, LocalDate asOf) {
    if (employee.getUserId() == null) {
      return Money.ofMinor(0L, baseCurrency);
    }
    UUID subject;
    try {
      subject = UUID.fromString(employee.getUserId());
    } catch (IllegalArgumentException e) {
      // A non-UUID sub cannot key a metric row (subject_id is a UUID). No metrics → no commission.
      return Money.ofMinor(0L, baseCurrency);
    }

    String monthPrefix = YearMonth.from(asOf).toString();
    long totalMetric = 0L;
    PeriodGrain resolvedGrain = null;
    List<MetricInput> rows =
        metricInputRepository.findByMetricKeyAndSubjectIdAndPeriodStartingWith(
            earningRule.getMetricKey(), subject, monthPrefix);
    for (MetricInput row : rows) {
      PeriodGrain rowGrain = periodGrainOf(row.getPeriod());
      if (resolvedGrain == null) {
        resolvedGrain = rowGrain;
      } else if (resolvedGrain != rowGrain) {
        throw new IllegalStateException(
            "Metric '"
                + earningRule.getMetricKey()
                + "' for subject "
                + subject
                + " in "
                + monthPrefix
                + " has rows at MIXED period grains — summing both would double-count. Fix the"
                + " upstream producer's grain");
      }
      totalMetric += row.getValue();
    }
    // SINGLE-CURRENCY ASSUMPTION (tracked follow-up): the metric feed carries a bare minor-units
    // `value` with NO currency, so the sum is denominated here in the company base currency. This
    // is
    // correct only while sales are in the base currency (multi-currency is flagged-simplified
    // system-wide, and sales-currency-vs-base is a pre-existing un-enforced gate — see the TODO in
    // restaurant SaleWriter). A cross-currency sale would be counted at face minor units. When
    // multi-currency lands, add an optional `currency` to MetricPublished and reject a metric whose
    // currency ≠ this base rather than blindly denominating.
    return Money.ofMinor(totalMetric, baseCurrency)
        .applyBasisPoints(earningRule.getPercentBasisPoints());
  }

  /** The two period grains a metric row may carry; the period-string shape distinguishes them. */
  private enum PeriodGrain {
    MONTHLY,
    DAILY
  }

  /**
   * Classifies a metric row's stored {@code period} by its string shape: {@code "YYYY-MM"} (length
   * 7) is MONTHLY, {@code "YYYY-MM-DD"} (length 10) is DAILY. Any other length is an unrecognised
   * producer grain and fails loudly rather than being silently summed.
   */
  private PeriodGrain periodGrainOf(String period) {
    return switch (period.length()) {
      case 7 -> PeriodGrain.MONTHLY;
      case 10 -> PeriodGrain.DAILY;
      default ->
          throw new IllegalStateException(
              "Metric period '" + period + "' is neither monthly (YYYY-MM) nor daily (YYYY-MM-DD)");
    };
  }

  // ---------------------------------------------------------------------
  // December / final-month Art-17 true-up (Track P phase P3)
  // ---------------------------------------------------------------------

  /**
   * The rule_key of the run's SINGLE resolved {@code ANNUAL_PROGRESSIVE} rule ({@code
   * PPH21_ARTICLE17} in the OFFICIAL dataset), or {@code null} if the frozen rule set carries none
   * (a tenant still on the illustrative-only catalog, or a future dataset that drops the family).
   * {@link #calculate} uses this as the sole guard on whether a December run wires the true-up at
   * all — a December run with no resolved ANNUAL_PROGRESSIVE rule stays on its ordinary monthly
   * branch, byte-identical to a pre-P3 run.
   */
  private String annualProgressiveRuleKey(Map<String, StatutoryRule> resolvedRules) {
    for (Map.Entry<String, StatutoryRule> entry : resolvedRules.entrySet()) {
      if (entry.getValue().getCalcType() == StatutoryCalcType.ANNUAL_PROGRESSIVE) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Swaps every statutory component whose OWN resolved rule is a MONTHLY income-tax family ({@code
   * PROGRESSIVE_BRACKET}/{@code TER_TABLE} — i.e. PPH21, whichever family the tenant's catalog
   * currently wires) onto {@code annualRuleKey} via {@link PayComponent#asAnnualTrueUpVariant};
   * every other component (the BPJS legs etc.) is left untouched. December must resolve EXACTLY ONE
   * income-tax family — never both the monthly branch AND the annual true-up, which would
   * double-tax the month. Computed ONCE per run (not per person): the swap depends only on the
   * frozen rule set, identical for every employee.
   */
  private List<PayComponent> swapForAnnualTrueUp(
      PayrollRun run,
      List<PayComponent> statutoryComponents,
      Map<String, StatutoryRule> resolvedRules,
      String annualRuleKey) {
    List<PayComponent> swapped = new ArrayList<>(statutoryComponents.size());
    int swappedCount = 0;
    for (PayComponent component : statutoryComponents) {
      StatutoryRule rule = resolvedRules.get(component.getStatutoryRuleKey());
      if (rule != null && isMonthlyIncomeTaxFamily(rule.getCalcType())) {
        swapped.add(component.asAnnualTrueUpVariant(annualRuleKey));
        swappedCount++;
      } else {
        swapped.add(component);
      }
    }
    // Fail-loud carry-in (P3/P4 review): a December run that resolves an ANNUAL_PROGRESSIVE rule
    // but swaps ZERO components would silently produce NO income-tax line at all this month — the
    // true-up would appear to "run" while doing nothing observable. That can only happen from a
    // catalog misconfiguration (no component's statutory_rule_key resolves to a monthly income-tax
    // family), so fail the run loudly instead of posting a December run with no PPh21 line.
    if (swappedCount == 0) {
      throw new IllegalStateException(
          "payroll_run "
              + run.getId()
              + " period "
              + run.getPeriod()
              + " is the December/final-month Art-17 true-up (resolved rule "
              + annualRuleKey
              + ") but swapped ZERO statutory components onto it — no pay_component's"
              + " statutory_rule_key currently resolves to a monthly income-tax family"
              + " (PROGRESSIVE_BRACKET/TER_TABLE); fix the catalog wiring before running December"
              + " payroll");
    }
    return List.copyOf(swapped);
  }

  private boolean isMonthlyIncomeTaxFamily(StatutoryCalcType calcType) {
    return calcType == StatutoryCalcType.PROGRESSIVE_BRACKET
        || calcType == StatutoryCalcType.TER_TABLE;
  }

  /**
   * Fail-loud/WARN carry-in (P3/P4 review): {@link #buildAnnualContext}'s historical reconstruction
   * uses the CURRENT frozen {@code resolvedRules} for every prior month (documented approximation —
   * see that method's Javadoc), which is only EXACT while a rule's figures were stable all fiscal
   * year. This WARNs ONCE per December run (not per employee — the frozen rule set is identical for
   * everyone in the run) naming every currently-resolved rule whose {@code effective_from} falls
   * AFTER January 1st of the run's fiscal year — a mid-year PATCH override — so an operator sees
   * the approximation is weaker for THIS run's history before trusting the true-up figure blindly.
   * Never blocks the run: the approximation is documented, accepted behaviour, not a hard failure.
   */
  private void warnIfAnyRuleEffectiveFromFallsInsideFiscalYear(
      PayrollRun run, Map<String, StatutoryRule> resolvedRules, String period) {
    LocalDate fiscalYearStart = LocalDate.of(Integer.parseInt(period.substring(0, 4)), 1, 1);
    for (StatutoryRule rule : resolvedRules.values()) {
      if (rule.getEffectiveFrom().isAfter(fiscalYearStart)) {
        log.warn(
            "payroll_run {} period {} (December true-up): resolved rule '{}' has effective_from {}"
                + " — INSIDE this fiscal year (after {}), meaning it was patched mid-year. The"
                + " December true-up's historical reconstruction uses this CURRENT rule for EVERY"
                + " prior month, which may misclassify a month before the patch landed — verify the"
                + " annual figure by hand for employees paid before {}",
            run.getId(),
            run.getPeriod(),
            rule.getRuleKey(),
            rule.getEffectiveFrom(),
            fiscalYearStart,
            rule.getEffectiveFrom());
      }
    }
  }

  /**
   * The full active pay-component catalog keyed by {@code component_key} — fetched ONCE per
   * December run and shared across every employee's {@link #buildAnnualContext} call, which reads
   * it to resolve each historical {@link PayslipLine}'s CURRENT taxability / tax-base flags (see
   * that method's historical-reconstruction javadoc).
   */
  private Map<String, PayComponent> catalogByComponentKey() {
    Map<String, PayComponent> byKey = new LinkedHashMap<>();
    for (PayComponent component : payComponentRepository.findByActiveTrueOrderByDisplayOrderAsc()) {
      byKey.put(component.getComponentKey(), component);
    }
    return byKey;
  }

  /**
   * Builds the December/final-month Art-17 {@link AnnualContext} for one employee: decrypts and
   * Money-sums their ACTIVE prior-period payslip lines this fiscal year ({@link
   * PayslipLineRepository#findActiveLinesForEmployeeYear}) — the reconciliation notes' documented
   * choice over a separate {@code payroll_ytd} accumulator table (immutable payslip lines are the
   * single source of truth; an accumulator is a perf fallback only if run cost ever bites).
   *
   * <p><strong>Historical-reconstruction approximation — documented honestly (ADR 0031 P3 residual
   * note).</strong> {@code payslip_line} stamps {@code component_key}/{@code kind}/{@code bearer}
   * but NOT whether the component was taxable, nor whether its rule's {@code
   * employer_adds_to_tax_base}/{@code reduces_tax_base} flags held, AT THE TIME each historical
   * line was produced — those flags live on the MUTABLE {@code pay_component} catalog and the
   * effective-dated {@code statutory_rule}, and reconstructing them exactly as they stood in, say,
   * March would require re-resolving March's as-of rule set for every prior month individually.
   * This method instead uses the CURRENT catalog / the CURRENT frozen {@code resolvedRules} (the
   * SAME map this December run just resolved) for every historical line. This is CORRECT whenever a
   * component's taxability and a rule's tax-base flags stayed stable all year (the expected, common
   * case — the OFFICIAL dataset's flags do not change mid-year in the ordinary course), but it CAN
   * mis-classify a prior month's line if the catalog or a rule's {@code base_kind}/{@code
   * employer_adds_to_tax_base}/{@code reduces_tax_base} were edited mid-year (e.g. a PATCH override
   * that changes a BPJS leg's tax treatment in, say, July). That edge case is a tracked follow-up,
   * never silently claimed to be handled here.
   *
   * <ul>
   *   <li>{@code cumulativeGrossBrutoMinor} = Σ(EARNING lines whose CURRENT catalog component is
   *       {@code taxable}) + Σ(EMPLOYER-bearer DEDUCTION lines whose CURRENT resolved rule is
   *       {@code PERCENTAGE_CEILING} with {@code employer_adds_to_tax_base = true}).
   *   <li>{@code cumulativeDeductibleSocialMinor} = Σ(EMPLOYEE-bearer DEDUCTION lines whose CURRENT
   *       resolved rule is {@code PERCENTAGE_CEILING} with {@code reduces_tax_base = true} — {@code
   *       JHT_EE}/{@code JP_EE} in the OFFICIAL dataset; BPJS-Kesehatan-EE is correctly excluded,
   *       its rule has {@code reduces_tax_base = false}).
   *   <li>{@code cumulativeWithheldMinor} = Σ(DEDUCTION lines whose CURRENT resolved rule is an
   *       income-tax family — {@code PROGRESSIVE_BRACKET}/{@code TER_TABLE}/{@code
   *       ANNUAL_PROGRESSIVE} — i.e. every historical PPh21 line, whichever branch produced it).
   *   <li>{@code monthsInYear} = the count of DISTINCT active prior periods carrying a line for
   *       this employee, plus 1 (this month).
   * </ul>
   *
   * <p>A DEDUCTION line whose CURRENT catalog component carries no {@code statutory_rule_key} (a
   * non-statutory deduction, e.g. a loan repayment) — or whose component was removed from the
   * catalog entirely — contributes to neither sum: it never affected the tax base and was never
   * PPh21 either way.
   */
  private AnnualContext buildAnnualContext(
      UUID employeeId,
      String period,
      String baseCurrency,
      Map<String, StatutoryRule> resolvedRules,
      Map<String, PayComponent> catalogByKey) {
    String yearPrefix = period.substring(0, 4);
    List<PayslipLine> priorLines =
        payslipLineRepository.findActiveLinesForEmployeeYear(employeeId, yearPrefix, period);
    List<String> priorPeriods =
        payslipLineRepository.findActivePriorPeriodsForEmployeeYear(employeeId, yearPrefix, period);

    Money grossBruto = Money.ofMinor(0L, baseCurrency);
    Money deductibleSocial = Money.ofMinor(0L, baseCurrency);
    Money withheld = Money.ofMinor(0L, baseCurrency);

    for (PayslipLine line : priorLines) {
      Money amount = line.getAmount();
      PayComponent current = catalogByKey.get(line.getComponentKey());

      if (line.getKind() == PayComponentKind.EARNING) {
        if (current != null && current.isTaxable()) {
          grossBruto = grossBruto.plus(amount);
        }
        continue;
      }

      // DEDUCTION line: classify by the CURRENT catalog component's rule family (see javadoc).
      String ruleKey = current != null ? current.getStatutoryRuleKey() : null;
      StatutoryRule rule = ruleKey != null ? resolvedRules.get(ruleKey) : null;
      if (rule == null) {
        continue; // non-statutory deduction (e.g. a loan) — no tax-base effect either way
      }

      if (isMonthlyIncomeTaxFamily(rule.getCalcType())
          || rule.getCalcType() == StatutoryCalcType.ANNUAL_PROGRESSIVE) {
        withheld = withheld.plus(amount);
      } else if (rule.getCalcType() == StatutoryCalcType.PERCENTAGE_CEILING) {
        StatutoryParams.CeilingParams params = StatutoryParams.ceiling(rule.getParamsJson());
        if (line.getBearer() == PayComponentBearer.EMPLOYER) {
          if (params.employerAddsToTaxBase()) {
            grossBruto = grossBruto.plus(amount);
          }
        } else if (params.reducesTaxBase()) {
          deductibleSocial = deductibleSocial.plus(amount);
        }
      }
    }

    int monthsInYear = priorPeriods.size() + 1;
    return new AnnualContext(
        grossBruto.amountMinor(),
        deductibleSocial.amountMinor(),
        withheld.amountMinor(),
        monthsInYear);
  }

  // ---------------------------------------------------------------------
  // Allocation
  // ---------------------------------------------------------------------

  private List<AllocatedRow> allocateForPerson(
      UUID employeeId,
      PersonResult result,
      PersonInput personInput,
      LocalDate asOf,
      String baseCurrency) {
    Map<String, Money> laborCostByGlAccount = laborCostByGlAccount(result, baseCurrency);
    List<UUID> outlets = outletsForEmployee(employeeId, asOf);
    if (outlets.isEmpty()) {
      // No outlet assignment in the period (on leave / between assignments). Rather than silently
      // dropping the cost (which breaks the run-level exact-sum invariant) or aborting the whole
      // batch, route this person's employer labor cost to an explicit, clearly-marked UNALLOCATED
      // suspense bucket so the run completes and finance sees the cost — collapsed to ONE bucket
      // regardless of its original GL account (unlike the per-outlet path below, the UNALLOCATED
      // suspense is deliberately a single catch-all, Track P Phase P7 unaffected here). Exact by
      // definition: the single bucket carries 100% of the (EXPENSE_REIMBURSEMENT-excluded) cost.
      Money unallocatedCost = sumMoney(laborCostByGlAccount.values(), baseCurrency);
      log.warn(
          "payroll_run person {} has NO outlet assignment in period {} — routing {} of employer"
              + " labor cost to the UNALLOCATED suspense bucket ({}/{})",
          employeeId,
          asOf,
          unallocatedCost.currency().getCurrencyCode(),
          UNALLOCATED_OUTLET,
          UNALLOCATED_GL_ACCOUNT);
      return List.of(
          new AllocatedRow(
              employeeId,
              UNALLOCATED_OUTLET,
              UNALLOCATED_OUTLET,
              UNALLOCATED_GL_ACCOUNT,
              unallocatedCost,
              (int) 10_000L));
    }

    // Enforce the same-legal-employer invariant at PAY time (design §4 — reuse resolveLegalEmployer
    // + ConflictingLegalEmployerException) across the person's concurrent outlets.
    UUID legalEmployer = null;
    Map<UUID, UUID> outletLegalEmployer = new LinkedHashMap<>();
    for (UUID outletId : outlets) {
      UUID resolved = resolveLegalEmployer(outletId);
      if (legalEmployer == null) {
        legalEmployer = resolved;
      } else if (!legalEmployer.equals(resolved)) {
        throw new ConflictingLegalEmployerException(employeeId, resolved, legalEmployer);
      }
      outletLegalEmployer.put(outletId, resolved);
    }

    // Attributable earnings per outlet: base split equally across concurrent outlets. The SAME
    // per-outlet share ratio applies to EVERY gl-account group below — the share is about WHERE the
    // person worked, not WHICH component the cost belongs to.
    Money base = personInput.basePay();
    Money perOutletBase = base.mulDiv(1L, outlets.size());
    Money totalEarnings = Money.ofMinor(0L, baseCurrency);
    for (UUID outletId : outlets) {
      totalEarnings = totalEarnings.plus(perOutletBase);
    }

    // Track P Phase P7 (reconciliation #4) — LaborCostAllocated buckets SPLIT per component GL
    // account (5100 salary / 5130 overtime / 5200 BPJS-ER / ...) instead of collapsing everything
    // onto BASE's gl account: one allocator invocation PER non-zero gl-account group, each sharing
    // the SAME outlet earnings-share ratios. EXPENSE_REIMBURSEMENT is already excluded (see {@link
    // #laborCostByGlAccount}).
    List<AllocatedRow> allRows = new ArrayList<>();
    for (Map.Entry<String, Money> group : laborCostByGlAccount.entrySet()) {
      if (group.getValue().isZero()) {
        continue; // zero-amount buckets are omitted, mirroring computeLiabilityBuckets' convention
      }
      List<OutletShare> shares = new ArrayList<>();
      for (UUID outletId : outlets) {
        shares.add(
            new OutletShare(
                outletId, outletLegalEmployer.get(outletId), group.getKey(), perOutletBase));
      }
      allRows.addAll(
          allocator.allocate(
              new PersonAllocation(
                  employeeId, group.getValue(), totalEarnings, List.copyOf(shares))));
    }
    return allRows;
  }

  /**
   * Groups this person's employer-borne labor cost by {@code gl_account} (Track P Phase P7): every
   * EARNING line (BASE, allowances, commission, OVERTIME, UNPAID_LEAVE — including its SIGNED
   * NEGATIVE amount, which correctly nets down the 5100-SALARY bucket) PLUS every EMPLOYER-bearing
   * DEDUCTION line (the BPJS-ER legs), EXCLUDING {@code EXPENSE_REIMBURSEMENT} entirely (it is not
   * labor cost — the expense was already recognized at claim approval; see {@link
   * #computeLiabilityBuckets}'s Javadoc for the parallel NET_WAGES_PAYABLE reasoning). An
   * EMPLOYEE-bearing DEDUCTION (PPh21, BPJS-EE, a future loan) reduces NET pay, never labor cost,
   * and is correctly excluded here too.
   */
  private Map<String, Money> laborCostByGlAccount(PersonResult result, String baseCurrency) {
    Map<String, Money> byGlAccount = new LinkedHashMap<>();
    for (ComputedLine line : result.lines()) {
      PayComponent component = line.component();
      if (ExpenseClaimPayrollLinker.COMPONENT_KEY_EXPENSE_REIMBURSEMENT.equals(
          component.getComponentKey())) {
        continue;
      }
      boolean isLaborCost =
          component.getKind() == PayComponentKind.EARNING
              || component.getBearer() == PayComponentBearer.EMPLOYER;
      if (!isLaborCost) {
        continue;
      }
      byGlAccount.merge(component.getGlAccount(), line.amount(), Money::plus);
    }
    if (byGlAccount.isEmpty()) {
      // Defensive: BASE always contributes an entry (even a zero-amount one), so this should be
      // unreachable — but never silently return an empty map that could mask a wiring bug.
      byGlAccount.put("5100-SALARY", Money.ofMinor(0L, baseCurrency));
    }
    return byGlAccount;
  }

  private Money sumMoney(java.util.Collection<Money> amounts, String baseCurrency) {
    Money total = Money.ofMinor(0L, baseCurrency);
    for (Money amount : amounts) {
      total = total.plus(amount);
    }
    return total;
  }

  private UUID resolveLegalEmployer(UUID orgUnitId) {
    return orgUnitProjectionRepository
        .findById(orgUnitId)
        .map(OrgUnitProjection::getLegalEmployerId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown org unit "
                        + orgUnitId
                        + " (not in the local org read model); cannot resolve its legal employer"));
  }

  private List<UUID> outletsForEmployee(UUID employeeId, LocalDate asOf) {
    List<UUID> outlets = new ArrayList<>();
    for (Assignment assignment : assignmentRepository.findByEmployeeId(employeeId)) {
      if (assignment.overlaps(asOf, asOf) && !outlets.contains(assignment.getOrgUnitId())) {
        outlets.add(assignment.getOrgUnitId());
      }
    }
    return outlets;
  }

  private void assertAllocationSumsToTotal(
      PayrollRun run, List<AllocatedRow> rows, Money totalLaborCost, String baseCurrency) {
    Money sum = Money.ofMinor(0L, baseCurrency);
    for (AllocatedRow row : rows) {
      sum = sum.plus(row.amount());
    }
    if (!sum.equals(totalLaborCost)) {
      throw new IllegalStateException(
          "Allocation sum "
              + sum.amountMinor()
              + " does not equal total labor cost "
              + totalLaborCost.amountMinor()
              + " for payroll_run "
              + run.getId()
              + " period "
              + run.getPeriod()
              + " (exact-sum invariant violated; run fails)");
    }
  }

  private Map<OutletGl, Money> aggregateBuckets(PayrollRun run) {
    Map<OutletGl, Money> buckets = new LinkedHashMap<>();
    for (LaborCostAllocation allocation : allocationRepository.findByPayrollRunId(run.getId())) {
      OutletGl key = new OutletGl(allocation.getOutletOrgUnitId(), allocation.getGlAccount());
      Money amount = allocation.getAmount();
      buckets.merge(key, amount, Money::plus);
    }
    return buckets;
  }

  // ---------------------------------------------------------------------
  // Persistence helpers
  // ---------------------------------------------------------------------

  private void persistPayslipLines(
      PayrollRun run, UUID employeeId, PersonResult result, String tenant) {
    for (ComputedLine line : result.lines()) {
      PayComponent component = line.component();
      PayslipLine entity =
          new PayslipLine(
              run.getId(),
              employeeId,
              component.getId(),
              component.getComponentKey(),
              component.getKind(),
              component.getBearer(),
              component.getGlAccount(),
              line.amount(),
              line.calcBasis(),
              line.ruleVersion(),
              line.illustrative());
      entity.setCompanyId(tenant);
      payslipLineRepository.save(entity);
    }
  }

  private int nextRunSeq(String period) {
    return runRepository.findByPeriodOrderByRunSeqDesc(period).stream()
        .findFirst()
        .map(r -> r.getRunSeq() + 1)
        .orElse(1);
  }

  private List<PayComponent> activeStatutoryComponents() {
    List<PayComponent> statutory = new ArrayList<>();
    for (PayComponent component : payComponentRepository.findByActiveTrueOrderByDisplayOrderAsc()) {
      if (component.getStatutoryRuleKey() != null) {
        statutory.add(component);
      }
    }
    return statutory;
  }

  private PayComponent requireComponent(String key) {
    return payComponentRepository
        .findByComponentKey(key)
        .orElseThrow(
            () -> new IllegalStateException("Required pay component '" + key + "' is not seeded"));
  }

  /** A (outlet, gl_account) bucket key for the aggregated LaborCostAllocated events. */
  private record OutletGl(UUID outletId, String glAccount) {}
}
