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
          // The self-contained top-up (skip-if-identical for the 9 unchanged rules; inserts only
          // the 3 new work-input components).
          var summary = officialSeeder.seed("ID-2026.2");
          assertThat(summary.rulesInserted()).isZero();
          assertThat(summary.componentsInserted()).isEqualTo(3);
          return null;
        });
    return outlet;
  }

  private UUID makeEmployeeWithPackage(UUID outlet, String nik, String bankAccount)
      throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () -> {
          UUID id =
              employeeService
                  .create(new CreateEmployeeCommand("Budi", "TK0", nik, bankAccount))
                  .getId();
          employeeService.linkUser(id, EMPLOYEE_ACTOR, null);
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
    UUID claimId =
        TenantContext.callAs(
            TENANT_A,
            EMPLOYEE_ACTOR,
            () -> {
              UUID categoryId =
                  categoryWriter.create("Supplies-" + employeeId, "supplies", false).getId();
              UUID id =
                  claimService
                      .create(
                          new CreateClaimCommand(
                              categoryId,
                              amountMinor,
                              IDR,
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

  private long lineAmount(List<PayslipLineResponse> lines, String componentKey) {
    return lines.stream()
        .filter(l -> l.componentKey().equals(componentKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no payslip line for " + componentKey))
        .amountMinor();
  }
}
