package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.CalcType;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PendingWorkEntriesException;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.PayslipLineResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.messaging.LaborCostAllocatedSchema;
import id.co.nativeapp.employee.payroll.messaging.PayrollLiabilitiesPostedSchema;
import id.co.nativeapp.employee.payroll.repository.CompensationPackageRepository;
import id.co.nativeapp.employee.payroll.repository.EarningRuleRepository;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.OfficialStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Track P Phase P7, end-to-end over a real RLS-enforcing PostgreSQL: a payroll run that consumes
 * APPROVED overtime, APPROVED unpaid leave, and a linked expense-claim reimbursement, all in one
 * pass — the crux of the phase (ADR 0032/0030 P7 addenda, the NET_WAGES_PAYABLE / employer-cost
 * split). Every scenario seeds {@code ID-2026.1} then {@code ID-2026.2} (the self-contained top-up,
 * proving skip-if-identical along the way) so OVERTIME/UNPAID_LEAVE/EXPENSE_REIMBURSEMENT actually
 * ride the payslip.
 */
@SpringBootTest
class PayrollWorkInputsEndToEndTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final String EMPLOYEE_ACTOR = "aaaaaaaa-2222-2222-2222-222222222222";
  // A SECOND linked employee identity (P7 review W4) — a Keycloak sub links to exactly one
  // employee, so a test seeding two employee-linked-to-a-user records needs two actor subs.
  private static final String EMPLOYEE_ACTOR_2 = "aaaaaaaa-4444-4444-4444-444444444444";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  // 17,300,000 / 173 = 100,000 exactly — a clean overtime worked example (mirrors
  // WorkInputCalculatorTest).
  private static final long BASE_MINOR = 17_300_000L;
  private static final long CLAIM_MINOR = 250_000L;

  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseClaimService claimService;
  @Autowired private LeaveRequestService leaveRequestService;
  @Autowired private OvertimeEntryService overtimeEntryService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private PayrollRunReader payrollRunReader;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TestCustomComponentSeeder customComponentSeeder;

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * A test-only {@code REQUIRES_NEW} unit of work that seeds a CUSTOM {@link PayComponent} + a
   * FIXED_AMOUNT {@link EarningRule} for the bucket-membership coverage test below — mirrors {@code
   * ExpenseClaimPayrollLinkerIntegrationTest.TestPayslipLineWriter}'s rationale exactly: a real JPA
   * write via the Spring proxy so {@code RlsAutoApplyAspect} binds the tenant GUC AND {@link
   * id.co.nativeapp.employee.payroll.domain.MoneyPiiConverter} genuinely encrypts the fixed amount
   * (rule 6) — no admin endpoint exists for the catalog in this codebase.
   */
  @TestConfiguration
  static class TestCustomComponentSeederConfig {
    @Bean
    TestCustomComponentSeeder testCustomComponentSeeder(
        PayComponentRepository payComponentRepository,
        EarningRuleRepository earningRuleRepository,
        CompensationPackageRepository compensationPackageRepository) {
      return new TestCustomComponentSeeder(
          payComponentRepository, earningRuleRepository, compensationPackageRepository);
    }
  }

  static class TestCustomComponentSeeder {
    private final PayComponentRepository payComponentRepository;
    private final EarningRuleRepository earningRuleRepository;
    private final CompensationPackageRepository compensationPackageRepository;

    TestCustomComponentSeeder(
        PayComponentRepository payComponentRepository,
        EarningRuleRepository earningRuleRepository,
        CompensationPackageRepository compensationPackageRepository) {
      this.payComponentRepository = payComponentRepository;
      this.earningRuleRepository = earningRuleRepository;
      this.compensationPackageRepository = compensationPackageRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID seedFixedLoanDeduction(UUID employeeId, String tenant, long amountMinor) {
      PayComponent component =
          new PayComponent(
              "LOAN_REPAYMENT",
              PayComponentKind.DEDUCTION,
              CalcType.FIXED,
              PayComponentBearer.EMPLOYEE,
              "2900-LOAN",
              false,
              null,
              50);
      component.setCompanyId(tenant);
      payComponentRepository.save(component);

      CompensationPackage pkg = compensationPackageRepository.findByEmployeeId(employeeId).get(0);
      EarningRule rule =
          EarningRule.fixedAmount(
              pkg.getId(),
              component.getId(),
              Money.ofMinor(amountMinor, "IDR"),
              LocalDate.of(2026, 1, 1),
              LocalDate.of(9999, 12, 31));
      rule.setCompanyId(tenant);
      earningRuleRepository.save(rule);
      return component.getId();
    }

    /**
     * Seeds a MINIMAL, ISOLATED catalog — BASE + UNPAID_LEAVE + EXPENSE_REIMBURSEMENT ONLY, ZERO
     * statutory rules/BPJS/PPh21 — bypassing BOTH {@code illustrativeSeeder} and {@code
     * officialSeeder} entirely. Used by the W1/S2 "cash circle" adversarial tests (P7 review): with
     * NO other statutory deductions in play, {@code net == reimbursement} EXACTLY when labor floors
     * to zero, an isolated proof of the unpaid-leave clamp + reimbursement-exclusion logic that is
     * NOT confounded by W5's base_kind=BASE_PAY fix (which decouples BPJS from unpaid-leave-reduced
     * pay — see {@code PayrollWorkInputsEndToEndTest}'s adversarial tests for the honest,
     * non-isolated proof of what happens once real BPJS legs are also in play).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void seedMinimalWorkInputCatalog(String tenant) {
      PayComponent base =
          new PayComponent(
              "BASE",
              PayComponentKind.EARNING,
              CalcType.FIXED,
              PayComponentBearer.EMPLOYEE,
              "5100-SALARY",
              true,
              null,
              0);
      base.setCompanyId(tenant);
      payComponentRepository.save(base);

      PayComponent unpaidLeave =
          new PayComponent(
              "UNPAID_LEAVE",
              PayComponentKind.EARNING,
              CalcType.WORK_INPUT_DERIVED,
              PayComponentBearer.EMPLOYEE,
              "5100-SALARY",
              true,
              null,
              11);
      unpaidLeave.setCompanyId(tenant);
      payComponentRepository.save(unpaidLeave);

      PayComponent reimbursement =
          new PayComponent(
              "EXPENSE_REIMBURSEMENT",
              PayComponentKind.EARNING,
              CalcType.WORK_INPUT_DERIVED,
              PayComponentBearer.EMPLOYEE,
              "5300-EXPENSE-REIMBURSEMENT",
              false,
              null,
              40);
      reimbursement.setCompanyId(tenant);
      payComponentRepository.save(reimbursement);
    }

    /**
     * P7 review W2: seeds ONLY a misconfigured {@code taxable=true} EXPENSE_REIMBURSEMENT component
     * (plus BASE, so a run has something to compute) — proves {@code PayrollRunWriter} fails the
     * run loudly rather than silently taxing a reimbursement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void seedCatalogWithTaxableReimbursementMisconfiguration(String tenant) {
      PayComponent base =
          new PayComponent(
              "BASE",
              PayComponentKind.EARNING,
              CalcType.FIXED,
              PayComponentBearer.EMPLOYEE,
              "5100-SALARY",
              true,
              null,
              0);
      base.setCompanyId(tenant);
      payComponentRepository.save(base);

      PayComponent reimbursement =
          new PayComponent(
              "EXPENSE_REIMBURSEMENT",
              PayComponentKind.EARNING,
              CalcType.WORK_INPUT_DERIVED,
              PayComponentBearer.EMPLOYEE,
              "5300-EXPENSE-REIMBURSEMENT",
              true, // MISCONFIGURED: must be false (ADR 0030 §6 / ADR 0032 §P7 addendum)
              null,
              40);
      reimbursement.setCompanyId(tenant);
      payComponentRepository.save(reimbursement);
    }
  }

  // ---------------------------------------------------------------------
  // The crux: overtime + unpaid leave + a linked claim reimbursement, all in one run.
  // ---------------------------------------------------------------------

  @Test
  void overtimeUnpaidLeaveAndALinkedClaimReimbursementAllRideTheSamePayslip() throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3221111111111111", "1111222233334444");
    UUID claimId = approvedPayrollClaim(employeeId, "Toko ATK", CLAIM_MINOR);

    // 3h weekday overtime (approved) = 1.5x hour 1 + 2x hours 2-3 @ hourly 100,000 = 550,000.
    UUID overtimeEntryId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                overtimeEntryService
                    .create(LocalDate.of(2026, 8, 10), 180, DayKind.WEEKDAY, "ot-key-1")
                    .getId());
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> overtimeEntryService.approve(overtimeEntryId, "ok", "ot-appr-1"));

    // 2 unpaid days (single-month, W2) -> -(17,300,000 * 2 / 21) = -1,647,619 (HALF_EVEN).
    UUID leaveRequestId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.UNPAID,
                        LocalDate.of(2026, 8, 3),
                        LocalDate.of(2026, 8, 4),
                        2,
                        "leave-key-1")
                    .getId());
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> leaveRequestService.approve(leaveRequestId, "ok", "leave-appr-1"));

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));

    assertThat(run.status()).isEqualTo("POSTED");

    // ---- the three work-input lines carry the hand-derived amounts (decrypted, authorized read)
    // --
    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(run.id(), employeeId));
    assertThat(lineAmount(lines, "OVERTIME")).isEqualTo(550_000L);
    assertThat(lineAmount(lines, "UNPAID_LEAVE")).isEqualTo(-1_647_619L);
    assertThat(lineAmount(lines, "EXPENSE_REIMBURSEMENT")).isEqualTo(CLAIM_MINOR);
    // Non-taxable: EXPENSE_REIMBURSEMENT never enters the tax/BPJS base — proven indirectly by the
    // exact-additive gross identity below (it holds ONLY if the reimbursement rides gross without
    // perturbing any OTHER line's computed amount — a taxable treatment would change every
    // percentage-of-gross deduction and break this identity).
    long grossTotal = 550_000L - 1_647_619L + BASE_MINOR + CLAIM_MINOR;
    assertThat(run.grossTotalMinor()).isEqualTo(grossTotal);

    // ---- LaborCostAllocated buckets EXCLUDE the reimbursement entirely (Track P Phase P7)
    // --------
    List<Map<String, Object>> allocatedRows =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'LaborCostAllocated' AND aggregate_id = ?",
            run.id().toString());
    long allocatedSum = 0L;
    boolean sawOvertimeBucket = false;
    for (Map<String, Object> row : allocatedRows) {
      GenericRecord rec =
          AvroSerde.deserialize((byte[]) row.get("payload"), LaborCostAllocatedSchema.schema());
      allocatedSum += (long) rec.get("amount_minor");
      if ("5130-OVERTIME".equals(rec.get("gl_account").toString())) {
        sawOvertimeBucket = true;
        // The gl-hint bucket split (reconciliation #4): overtime lands in its OWN 5130 bucket.
        assertThat((long) rec.get("amount_minor")).isEqualTo(550_000L);
      }
      // No bucket ever carries the 5300-EXPENSE-REIMBURSEMENT gl account.
      assertThat(rec.get("gl_account").toString()).isNotEqualTo("5300-EXPENSE-REIMBURSEMENT");
    }
    assertThat(sawOvertimeBucket).as("a dedicated 5130-OVERTIME bucket was emitted").isTrue();
    long expectedLaborCost =
        run.grossTotalMinor() - CLAIM_MINOR + run.employerContributionTotalMinor();
    assertThat(allocatedSum).isEqualTo(expectedLaborCost);

    // ---- PayrollLiabilitiesPosted: employerCostTotal excludes reimbursement; NET_WAGES_PAYABLE =
    // --
    // ---- net - reimbursement; the full identity balances (never emitted otherwise, ADR 0032).
    // ----
    Map<String, Object> liabilitiesRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'PayrollLiabilitiesPosted' AND"
                + " aggregate_id = ?",
            run.id().toString());
    GenericRecord liabilitiesRecord =
        AvroSerde.deserialize(
            (byte[]) liabilitiesRow.get("payload"), PayrollLiabilitiesPostedSchema.schema());
    assertThat(liabilitiesRecord.get("employer_cost_total_minor")).isEqualTo(expectedLaborCost);

    @SuppressWarnings("unchecked")
    List<GenericRecord> buckets = (List<GenericRecord>) liabilitiesRecord.get("liabilities");
    Map<String, Long> byRole = new java.util.LinkedHashMap<>();
    for (GenericRecord bucket : buckets) {
      byRole.put(bucket.get("liability_role").toString(), (Long) bucket.get("amount_minor"));
    }
    long expectedNetWagesPayable = run.netTotalMinor() - CLAIM_MINOR;
    assertThat(byRole).containsEntry("NET_WAGES_PAYABLE", expectedNetWagesPayable);
    long bucketSum = byRole.values().stream().mapToLong(Long::longValue).sum();
    assertThat(bucketSum).isEqualTo(expectedLaborCost);

    // ---- the linked claim is REIMBURSED and ExpenseReimbursementSettled(PAYROLL) was emitted
    // -----
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED'",
                claimId))
        .isEqualTo(1L);
    Map<String, Object> settledRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                + " aggregate_id = ?",
            claimId.toString());
    GenericRecord settledRecord =
        AvroSerde.deserialize(
            (byte[]) settledRow.get("payload"),
            id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema.schema());
    assertThat(settledRecord.get("settlement_kind").toString()).isEqualTo("PAYROLL");
    assertThat(settledRecord.get("amount_minor")).isEqualTo(CLAIM_MINOR);

    // ---- work_inputs_json freezes exactly what was consumed (reproducibility)
    // ---------------------
    JsonNode workInputs = mapper.readTree(run.workInputsJson());
    JsonNode employeeNode = workInputs.get(employeeId.toString());
    assertThat(employeeNode.get("overtime").get("weekdayMinutes").asInt()).isEqualTo(180);
    assertThat(employeeNode.get("overtime").get("entryIds").get(0).asText())
        .isEqualTo(overtimeEntryId.toString());
    assertThat(employeeNode.get("unpaidLeave").get("days").asInt()).isEqualTo(2);
    assertThat(employeeNode.get("unpaidLeave").get("requestIds").get(0).asText())
        .isEqualTo(leaveRequestId.toString());
    assertThat(employeeNode.get("reimbursement").get("amountMinor").asLong())
        .isEqualTo(CLAIM_MINOR);
    // P7 review W3: the individual claim id(s) are frozen too, not just the aggregate total/count.
    assertThat(employeeNode.get("reimbursement").get("claimCount").asLong()).isEqualTo(1L);
    assertThat(employeeNode.get("reimbursement").get("claimIds").get(0).asText())
        .isEqualTo(claimId.toString());
  }

  // ---------------------------------------------------------------------
  // P7 review C1 (CRITICAL, 11-44% overpayment) — PP 35/2021's multiplier tiers reset PER CALENDAR
  // DAY, never aggregated across a whole month. Two hand-derived multi-day proofs against the real
  // {@code appendWorkInputs} writer path (WorkInputCalculatorTest's per-call tests only prove the
  // pure tier-walk is correct for ONE call; these prove the CALLER groups correctly).
  // ---------------------------------------------------------------------

  @Test
  void fiveWeekdayDaysOfTwoHoursOvertimeSumToSeventeenPointFiveTimesHourlyNotNineteenPointFive()
      throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3228888888888888", "8888999900001111");

    // 5 weekday days x 2h (120 min) each, on 5 DISTINCT calendar days in August.
    List<LocalDate> days =
        List.of(
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 4),
            LocalDate.of(2026, 8, 5),
            LocalDate.of(2026, 8, 6),
            LocalDate.of(2026, 8, 7));
    for (int i = 0; i < days.size(); i++) {
      LocalDate day = days.get(i);
      String key = "ot-c1-weekday-" + i;
      UUID entryId =
          TenantContext.callAs(
              TENANT_A,
              EMPLOYEE_ACTOR,
              () -> overtimeEntryService.create(day, 120, DayKind.WEEKDAY, key).getId());
      TenantContext.callAs(
          TENANT_A, ACTOR_A, () -> overtimeEntryService.approve(entryId, "ok", key + "-appr"));
    }

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(run.id(), employeeId));
    // PER-DAY correct: each day is 1h@1.5x + 1h@2.0x = 3.5x hourly; 5 days = 17.5x hourly.
    // hourly = 17,300,000 / 173 = 100,000. 17.5 * 100,000 = 1,750,000.
    // WRONGLY aggregated (the C1 bug): 1h@1.5x + 9h@2.0x (10h total) = 19.5x hourly = 1,950,000 —
    // an 11.4% overpayment this test proves does NOT happen.
    assertThat(lineAmount(lines, "OVERTIME")).isEqualTo(1_750_000L);
  }

  @Test
  void twoRestDaysOfEightHoursOvertimeSumToThirtyFourTimesHourlyNotFortyNine() throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3229999999999999", "9999000011112222");

    // 2 rest days x 8h (480 min) each, on 2 DISTINCT calendar days.
    List<LocalDate> days = List.of(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9));
    for (int i = 0; i < days.size(); i++) {
      LocalDate day = days.get(i);
      String key = "ot-c1-restday-" + i;
      UUID entryId =
          TenantContext.callAs(
              TENANT_A,
              EMPLOYEE_ACTOR,
              () -> overtimeEntryService.create(day, 480, DayKind.REST_DAY, key).getId());
      TenantContext.callAs(
          TENANT_A, ACTOR_A, () -> overtimeEntryService.approve(entryId, "ok", key + "-appr"));
    }

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(run.id(), employeeId));
    // PER-DAY correct: each 8h rest day = 7h@2x + 1h@3x = 17x hourly; 2 days = 34x hourly.
    // hourly = 100,000. 34 * 100,000 = 3,400,000.
    // WRONGLY aggregated (the C1 bug): 7h@2x + 1h@3x + 8h@4x (16h total) = 49x hourly = 4,900,000 —
    // a 44.1% overpayment this test proves does NOT happen.
    assertThat(lineAmount(lines, "OVERTIME")).isEqualTo(3_400_000L);
  }

  // ---------------------------------------------------------------------
  // P7 review W2 — a taxable=true EXPENSE_REIMBURSEMENT component is a catalog misconfiguration:
  // the run must fail LOUDLY (422), never silently tax the reimbursement.
  // ---------------------------------------------------------------------

  @Test
  void aTaxableExpenseReimbursementComponentFailsTheRunLoudlyInsteadOfSilentlyTaxingIt()
      throws Exception {
    UUID outlet = UUID.randomUUID();
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () ->
            orgProjectionService.apply(
                new OrgUnitProjectedEvent(
                    UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true)));
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          customComponentSeeder.seedCatalogWithTaxableReimbursementMisconfiguration(TENANT_A);
          return null;
        });
    UUID employeeId = makeEmployeeWithPackage(outlet, "3220000000000000", "0000111122223333");

    // The check fires at rule-resolution/freeze time, unconditionally — no claim/reimbursement
    // needs to actually be linked this run for the misconfiguration to be caught.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> payrollRunService.calculate(runCommand(employeeId), IDR)))
        .isInstanceOf(
            id.co.nativeapp.employee.payroll.domain.TaxableReimbursementComponentException.class)
        .hasMessageContaining("EXPENSE_REIMBURSEMENT")
        .hasMessageContaining("non-taxable");

    // The FAILED-audit-trail precedent (mirrors aPendingSubmittedOvertimeEntryBlocksTheWholeRun):
    // no CALCULATED/POSTED row exists for this period.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payroll_run WHERE period = '2026-08' AND status <> 'FAILED'"))
        .isZero();
  }

  // ---------------------------------------------------------------------
  // P7 review W1/S2 — the labor-pay floor + the "cash circle": an ISOLATED minimal catalog (BASE +
  // UNPAID_LEAVE + EXPENSE_REIMBURSEMENT only, ZERO BPJS/PPh21) so the identity is exact and not
  // confounded by W5's base_kind=BASE_PAY fix (which computes BPJS on the UNCLAMPED base pay
  // regardless of the unpaid-leave clamp — see the class-level note on {@code
  // TestCustomComponentSeeder#seedMinimalWorkInputCatalog} for why this is a SEPARATE, deliberately
  // narrower proof than the full-catalog crux test above).
  // ---------------------------------------------------------------------

  @Test
  void thirtyOneUnpaidDaysAgainstDivisor21ClampsLaborPayToExactlyZeroNeverNegative()
      throws Exception {
    UUID outlet = seedMinimalCatalogAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3225555555555555", "5555666677778888");

    // A single-month UNPAID request spanning the whole of August (31 days) — legal today (a
    // whole-month request cannot cross a calendar-month boundary, but CAN span up to 31 days within
    // one). Divisor 21 (the (5, 21) default work calendar): 31 > 21, so W1's clamp bites.
    UUID leaveRequestId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.UNPAID,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        31,
                        "leave-key-w1-31")
                    .getId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> leaveRequestService.approve(leaveRequestId, "ok", "leave-appr-w1"));

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(run.id(), employeeId));
    // The deduction clamps at EXACTLY -basePay (31 unpaid days, but only 21 of them can bite).
    assertThat(lineAmount(lines, "UNPAID_LEAVE")).isEqualTo(-BASE_MINOR);
    // No statutory deductions exist in this isolated catalog, so net == gross == 0 EXACTLY — never
    // negative (the absurd "employee owes the company money for taking unpaid leave" result W1
    // eliminates).
    assertThat(run.grossTotalMinor()).isZero();
    assertThat(run.netTotalMinor()).isZero();
    assertThat(run.netTotalMinor()).isGreaterThanOrEqualTo(0L);

    JsonNode workInputs = mapper.readTree(run.workInputsJson());
    JsonNode leaveNode = workInputs.get(employeeId.toString()).get("unpaidLeave");
    assertThat(leaveNode.get("days").asInt()).isEqualTo(31);
    assertThat(leaveNode.get("appliedDays").asInt())
        .isEqualTo(21); // clamped — frozen for the record
  }

  @Test
  void reimbursementExceedingZeroFlooredLaborNetEqualsExactlyTheReimbursementTheCashCircleHolds()
      throws Exception {
    UUID outlet = seedMinimalCatalogAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3226666666666666", "6666777788889999");
    // A reimbursement well above the (floored-to-zero) labor net for the month.
    long reimbursementMinor = 5_000_000L;
    UUID claimId = approvedPayrollClaim(employeeId, "Toko Elektronik", reimbursementMinor);

    UUID leaveRequestId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.UNPAID,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        31,
                        "leave-key-w1-circle")
                    .getId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> leaveRequestService.approve(leaveRequestId, "ok", "leave-appr-w1-circle"));

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    // The circle: with zero statutory deductions in play and labor pay floored to exactly zero
    // (base 17,300,000 fully offset by the clamped -17,300,000 UNPAID_LEAVE line), net == the
    // reimbursement EXACTLY — every rupiah the employee receives this run traces to the claim, none
    // to labor (S2, the coordinator's "assert the circle" requirement).
    assertThat(run.netTotalMinor()).isEqualTo(reimbursementMinor);
    assertThat(run.netTotalMinor()).isGreaterThanOrEqualTo(reimbursementMinor);

    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED'",
                claimId))
        .isEqualTo(1L);
  }

  @Test
  void exactlyTheDivisorOfUnpaidDaysFloorsLaborToZeroWithoutTrippingTheClampWarnPath()
      throws Exception {
    // S2 "zero-labor-earnings run": unpaid days == the divisor EXACTLY (21, not exceeding it) — no
    // clamping occurs (appliedDays is never written), yet labor net is STILL legitimately zero. A
    // payslip with net == 0 and NO reimbursement is a valid, non-error outcome, not a run failure.
    UUID outlet = seedMinimalCatalogAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3227777777777777", "7777888899990000");

    UUID leaveRequestId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                leaveRequestService
                    .create(
                        LeaveType.UNPAID,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 21),
                        21,
                        "leave-key-w1-exact")
                    .getId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> leaveRequestService.approve(leaveRequestId, "ok", "leave-appr-w1-exact"));

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");
    assertThat(run.grossTotalMinor()).isZero();
    assertThat(run.netTotalMinor()).isZero();

    List<PayslipLineResponse> lines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> payrollRunReader.findPayslipAuthorized(run.id(), employeeId));
    // 21 days / divisor 21 on base 17,300,000 = -17,300,000 exactly — base ÷ divisor × divisor, no
    // rounding remainder.
    assertThat(lineAmount(lines, "UNPAID_LEAVE")).isEqualTo(-BASE_MINOR);

    JsonNode workInputs = mapper.readTree(run.workInputsJson());
    JsonNode leaveNode = workInputs.get(employeeId.toString()).get("unpaidLeave");
    assertThat(leaveNode.get("days").asInt()).isEqualTo(21);
    assertThat(leaveNode.has("appliedDays")).as("no clamp fired at exactly the divisor").isFalse();
  }

  // ---------------------------------------------------------------------
  // P7 review W4 — per-claim (per-EMPLOYEE) settle gating: a currency-mismatched linked claim's
  // employee never gets an EXPENSE_REIMBURSEMENT payslip line, so their claim stays APPROVED+linked
  // (never settled for money never received) while a DIFFERENT employee's matching-currency claim
  // in
  // the SAME run settles normally.
  // ---------------------------------------------------------------------

  @Test
  void aCurrencyMismatchedLinkedClaimStaysApprovedAndLinkedWhileAnotherEmployeesClaimSettles()
      throws Exception {
    UUID outlet = seedMinimalCatalogAndOutlet();
    UUID settlesEmployee =
        makeEmployeeWithPackage(outlet, "3230000000000000", "0000111122223333", EMPLOYEE_ACTOR);
    UUID skippedEmployee =
        makeEmployeeWithPackage(outlet, "3231111111111111", "1111222233334444", EMPLOYEE_ACTOR_2);

    UUID settlesClaimId = approvedPayrollClaim(settlesEmployee, "Toko Kertas", 300_000L);
    // ExpenseClaimWriter enforces ONE currency per TENANT (ADR 0030 §9's v1 boundary — created
    // establishes IDR above), so a genuinely cross-currency CREATE is impossible via the API. The
    // anomaly this test proves resilient to is instead reached the way it would arise in practice —
    // a pre-existing row whose currency no longer matches what the run is invoked with (e.g. a data
    // migration, or a future multi-currency world) — a raw UPDATE bypassing the write-time guard,
    // exactly the "data anomaly" the linker's Javadoc already documents defending against.
    UUID skippedClaimId =
        approvedPayrollClaimInCurrency(
            skippedEmployee, "Foreign Vendor", 300_000L, IDR, EMPLOYEE_ACTOR_2);
    corruptClaimCurrencyAsTenant(TENANT_A, skippedClaimId, "USD");

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(
                        runCommand(List.of(settlesEmployee, skippedEmployee)), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    // The matching-currency claim settled: REIMBURSED + ExpenseReimbursementSettled(PAYROLL).
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'REIMBURSED'",
                settlesClaimId))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                settlesClaimId.toString()))
        .isEqualTo(1L);

    // The mismatched claim stayed APPROVED and STILL LINKED to this run (not settled, not released
    // yet — releaseForPeriod's POSTED-but-still-APPROVED branch recovers it on the NEXT calculate).
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                skippedClaimId,
                run.id()))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM outbox WHERE event_type = 'ExpenseReimbursementSettled' AND"
                    + " aggregate_id = ?",
                skippedClaimId.toString()))
        .isZero();

    // The skipped employee's payslip carries no EXPENSE_REIMBURSEMENT line at all.
    List<PayslipLineResponse> skippedLines =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> payrollRunReader.findPayslipAuthorized(run.id(), skippedEmployee));
    assertThat(skippedLines).noneMatch(l -> l.componentKey().equals("EXPENSE_REIMBURSEMENT"));

    // A fresh calculate() for the SAME period releases the still-APPROVED claim and re-links it.
    PayrollRunResponse run2 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculate(
                        runCommand(List.of(settlesEmployee, skippedEmployee)), IDR)));
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM expense_claim WHERE id = ? AND status = 'APPROVED' AND"
                    + " reimbursement_run_id = ?",
                skippedClaimId,
                run2.id()))
        .isEqualTo(1L);
  }

  // ---------------------------------------------------------------------
  // The pending-work-entries gate — an undecided SUBMITTED request blocks the WHOLE run (409).
  // ---------------------------------------------------------------------

  @Test
  void aPendingSubmittedOvertimeEntryBlocksTheWholeRunWith409() throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3222222222222222", "2222333344445555");

    TenantContext.callAs(
        TENANT_A,
        EMPLOYEE_ACTOR,
        () ->
            overtimeEntryService.create(
                LocalDate.of(2026, 8, 12), 60, DayKind.WEEKDAY, "ot-pending"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> payrollRunService.calculate(runCommand(employeeId), IDR)))
        .isInstanceOf(PendingWorkEntriesException.class);

    // PayrollRunService's uniform catch-and-record-FAILED audit trail engages for this exception
    // exactly like every other calculate() failure (IncompletePeriodException etc.) — a FAILED row
    // is recorded, but never a CALCULATED/POSTED one: the gate throws before any real compute work.
    assertThat(
            countAsTenant(
                TENANT_A,
                "SELECT count(*) FROM payroll_run WHERE period = '2026-08' AND status <> 'FAILED'"))
        .isZero();
  }

  // ---------------------------------------------------------------------
  // Reproducibility — recalculating after a NEW approval produces a DIFFERENT work_inputs_json that
  // explains BOTH runs (Track P Phase P7).
  // ---------------------------------------------------------------------

  @Test
  void recalculatingAfterANewApprovalProducesADifferentWorkInputsJsonExplainingBothRuns()
      throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3223333333333333", "3333444455556666");

    UUID firstEntryId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                overtimeEntryService
                    .create(LocalDate.of(2026, 8, 5), 60, DayKind.WEEKDAY, "ot-repro-1")
                    .getId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> overtimeEntryService.approve(firstEntryId, "ok", "ot-repro-1-appr"));

    PayrollRunResponse run1 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(payrollRunService.calculate(runCommand(employeeId), IDR)));
    JsonNode run1Inputs = mapper.readTree(run1.workInputsJson());
    assertThat(run1Inputs.get(employeeId.toString()).get("overtime").get("weekdayMinutes").asInt())
        .isEqualTo(60);

    // A NEW approved overtime entry lands after run1 calculated.
    UUID secondEntryId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () ->
                overtimeEntryService
                    .create(LocalDate.of(2026, 8, 6), 60, DayKind.WEEKDAY, "ot-repro-2")
                    .getId());
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> overtimeEntryService.approve(secondEntryId, "ok", "ot-repro-2-appr"));

    PayrollRunResponse run2 =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(payrollRunService.calculate(runCommand(employeeId), IDR)));
    assertThat(run2.id()).isNotEqualTo(run1.id());
    assertThat(run2.runSeq()).isEqualTo(run1.runSeq() + 1);

    JsonNode run2Inputs = mapper.readTree(run2.workInputsJson());
    assertThat(run2Inputs.get(employeeId.toString()).get("overtime").get("weekdayMinutes").asInt())
        .isEqualTo(120); // BOTH entries now — the second run's own re-resolution picked it up.
    // run1's OWN frozen json is untouched by the later re-run (immutable history).
    JsonNode run1InputsReread =
        mapper.readTree(
            TenantContext.callAs(
                TENANT_A,
                ACTOR_A,
                () -> payrollRunReader.findRun(run1.id()).orElseThrow().workInputsJson()));
    assertThat(
            run1InputsReread
                .get(employeeId.toString())
                .get("overtime")
                .get("weekdayMinutes")
                .asInt())
        .isEqualTo(60);
  }

  // ---------------------------------------------------------------------
  // Bucket-membership coverage: an unrecognized deduction component routes to the catch-all
  // OTHER_DEDUCTIONS_PAYABLE bucket (P3/P4 review carry-in).
  // ---------------------------------------------------------------------

  @Test
  void anUnrecognizedEmployeeDeductionComponentRoutesToOtherDeductionsPayable() throws Exception {
    UUID outlet = seedDatasetsAndOutlet();
    UUID employeeId = makeEmployeeWithPackage(outlet, "3224444444444444", "4444555566667777");

    // A LOAN_REPAYMENT component: EMPLOYEE-bearing DEDUCTION, no statutory_rule_key — the
    // GrossToNetCalculator's "OTHER DEDUCTIONS" step (step 4) picks it up from a FIXED_AMOUNT
    // EarningRule of kind DEDUCTION.
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> customComponentSeeder.seedFixedLoanDeduction(employeeId, TENANT_A, 100_000L));

    PayrollRunResponse run =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                PayrollRunResponse.from(
                    payrollRunService.calculateAndPost(runCommand(employeeId), IDR)));
    assertThat(run.status()).isEqualTo("POSTED");

    Map<String, Object> liabilitiesRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'PayrollLiabilitiesPosted' AND"
                + " aggregate_id = ?",
            run.id().toString());
    GenericRecord liabilitiesRecord =
        AvroSerde.deserialize(
            (byte[]) liabilitiesRow.get("payload"), PayrollLiabilitiesPostedSchema.schema());
    @SuppressWarnings("unchecked")
    List<GenericRecord> buckets = (List<GenericRecord>) liabilitiesRecord.get("liabilities");
    Map<String, Long> byRole = new java.util.LinkedHashMap<>();
    for (GenericRecord bucket : buckets) {
      byRole.put(bucket.get("liability_role").toString(), (Long) bucket.get("amount_minor"));
    }
    assertThat(byRole).containsEntry("OTHER_DEDUCTIONS_PAYABLE", 100_000L);
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private RunPayrollCommand runCommand(UUID employeeId) {
    return new RunPayrollCommand("2026-08", List.of(employeeId), List.of());
  }

  private RunPayrollCommand runCommand(List<UUID> employeeIds) {
    return new RunPayrollCommand("2026-08", employeeIds, List.of());
  }

  private UUID seedDatasetsAndOutlet() throws Exception {
    UUID outlet = UUID.randomUUID();
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () ->
            orgProjectionService.apply(
                new OrgUnitProjectedEvent(
                    UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true)));
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          illustrativeSeeder.seed(IDR);
          officialSeeder.seed("ID-2026.1");
          // The self-contained top-up: 4 rules (PPH21_TER/ARTICLE17, PTKP_RELIEF, BIAYA_JABATAN)
          // + OVERTIME_HOURLY are skip-if-identical (rule_version unchanged); the 5 BPJS rules are
          // a GENUINE revision (P7 review W5 — base_kind=BASE_PAY, rule_version bumped to
          // ID-2026.2) so they DO insert+supersede; 3 new work-input components insert.
          var summary = officialSeeder.seed("ID-2026.2");
          assertThat(summary.rulesInserted()).isEqualTo(5);
          assertThat(summary.rulesClosed()).isEqualTo(5);
          assertThat(summary.rulesSkipped())
              .isEqualTo(5); // PPH21_TER/ARTICLE17/PTKP/BIAYA_JABATAN/OVERTIME_HOURLY
          assertThat(summary.componentsInserted()).isEqualTo(3);
          return null;
        });
    return outlet;
  }

  /**
   * P7 review W1/S2: an outlet + the ISOLATED minimal catalog (BASE + UNPAID_LEAVE +
   * EXPENSE_REIMBURSEMENT, ZERO BPJS/PPh21 statutory rules) — bypasses both {@code
   * illustrativeSeeder} and {@code officialSeeder} entirely, so the "cash circle" identity holds
   * EXACTLY without any statutory deduction as a confounding variable.
   */
  private UUID seedMinimalCatalogAndOutlet() throws Exception {
    UUID outlet = UUID.randomUUID();
    TenantContext.runAs(
        TENANT_A,
        ACTOR_A,
        () ->
            orgProjectionService.apply(
                new OrgUnitProjectedEvent(
                    UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true)));
    TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          customComponentSeeder.seedMinimalWorkInputCatalog(TENANT_A);
          return null;
        });
    return outlet;
  }

  private UUID makeEmployeeWithPackage(UUID outlet, String nik, String bankAccount)
      throws Exception {
    return makeEmployeeWithPackage(outlet, nik, bankAccount, EMPLOYEE_ACTOR);
  }

  /**
   * P7 review W4: a variant that links a CALLER-SUPPLIED actor sub (rather than the shared {@code
   * EMPLOYEE_ACTOR}) — needed whenever a test seeds MORE THAN ONE employee-linked-to-a-user in the
   * same tenant, since a Keycloak sub can only ever link to ONE employee record ({@code
   * UserAlreadyLinkedException}).
   */
  private UUID makeEmployeeWithPackage(UUID outlet, String nik, String bankAccount, String actorSub)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          UUID id =
              employeeService
                  .create(new CreateEmployeeCommand("Budi", "TK0", nik, bankAccount))
                  .getId();
          employeeService.linkUser(id, actorSub, null);
          var contract =
              employeeService.addContract(
                  new AddContractCommand(
                      id,
                      "PERMANENT",
                      LEGAL_EMPLOYER,
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));
          compensationWriter.createPackage(
              id,
              contract.getId(),
              Money.ofMinor(BASE_MINOR, IDR),
              LocalDate.of(2026, 1, 1),
              LocalDate.of(9999, 12, 31));
          assignmentService.add(
              new AddAssignmentCommand(
                  id,
                  outlet,
                  null,
                  "cashier",
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31)));
          return id;
        });
  }

  private UUID approvedPayrollClaim(UUID employeeId, String merchant, long amountMinor)
      throws Exception {
    return approvedPayrollClaimInCurrency(employeeId, merchant, amountMinor, IDR, EMPLOYEE_ACTOR);
  }

  /** P7 review W4: a claim in a NON-base currency, to prove the per-employee settle-skip path. */
  private UUID approvedPayrollClaimInCurrency(
      UUID employeeId, String merchant, long amountMinor, String currency) throws Exception {
    return approvedPayrollClaimInCurrency(
        employeeId, merchant, amountMinor, currency, EMPLOYEE_ACTOR);
  }

  /** P7 review W4: as above, but created by a CALLER-SUPPLIED actor (a second linked employee). */
  private UUID approvedPayrollClaimInCurrency(
      UUID employeeId, String merchant, long amountMinor, String currency, String actorSub)
      throws Exception {
    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            actorSub,
            () -> {
              UUID categoryId =
                  categoryWriter.create("Supplies-" + employeeId, "supplies", false).getId();
              UUID id =
                  claimService
                      .create(
                          new CreateClaimCommand(
                              categoryId,
                              amountMinor,
                              currency,
                              LocalDate.of(2026, 8, 5),
                              merchant,
                              "note",
                              null))
                      .getId();
              claimService.submit(id, "submit-" + id);
              return id;
            });
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> claimService.approve(claimId, "ok", "approve-" + claimId));
    return claimId;
  }

  /**
   * P7 review W4: raw-UPDATEs a claim's {@code amount_currency} — bypassing {@code
   * ExpenseClaimWriter}'s create-time single-currency-per-tenant guard — to simulate the
   * "pre-existing mismatched row" data anomaly {@code PayrollRunWriter#reimbursementInfoByEmployee}
   * defends against. Mirrors {@link PostgresRlsTestBase#countAsTenant}'s raw-JDBC-with-GUC pattern
   * (a fresh {@code app_user} session, tenant GUC bound, connection closed after).
   */
  private static void corruptClaimCurrencyAsTenant(String tenant, UUID claimId, String currency) {
    try (java.sql.Connection con =
        java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD)) {
      try (java.sql.PreparedStatement set =
          con.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
        set.setString(1, tenant);
        set.execute();
      }
      try (java.sql.PreparedStatement update =
          con.prepareStatement("UPDATE expense_claim SET amount_currency = ? WHERE id = ?")) {
        update.setString(1, currency);
        update.setObject(2, claimId);
        int updated = update.executeUpdate();
        if (updated != 1) {
          throw new IllegalStateException(
              "corruptClaimCurrencyAsTenant expected to update exactly 1 row, updated " + updated);
        }
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(
          "corruptClaimCurrencyAsTenant failed for tenant " + tenant, e);
    }
  }

  private long lineAmount(List<PayslipLineResponse> lines, String componentKey) {
    return lines.stream()
        .filter(l -> l.componentKey().equals(componentKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no payslip line for " + componentKey))
        .amountMinor();
  }
}
