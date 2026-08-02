package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.domain.EmploymentContract;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.dto.UpdateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.OfficialStatutorySeedWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for the employee self-service surface ({@code /api/v1/me/**}).
 *
 * <p>The caller is resolved EXCLUSIVELY from {@code X-Actor} (the JWT sub the gateway injects) via
 * the V7 employee↔user link — never from a request parameter, so a caller can only ever see their
 * OWN data. The payslip detail is the FIRST caller of the authorized (decrypted) read: an
 * employee's own amounts are real; NIK/bank stay masked even to themselves. An unlinked login gets
 * a 404 not-linked problem, and a run without the caller's lines is a 404 (anti-enumeration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class MeEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String HR_ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final String PERIOD = "2026-07";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private EmployeeService employeeService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private IllustrativeStatutorySeedWriter illustrativeSeeder;
  @Autowired private OfficialStatutorySeedWriter officialSeeder;

  @Test
  void aLinkedEmployeeSeesTheirOwnProfilePayslipsAndRealAmountsOnly() throws Exception {
    seedIllustrative();
    UUID outlet = seedOrgUnit("outlet");
    String mySub = UUID.randomUUID().toString();
    String otherSub = UUID.randomUUID().toString();

    // Me: employee with salary 4,000,000.00 IDR; a colleague with a different salary.
    UUID me = createEmployee("Rina Kasir", "3206000000000001", "6666777788880001");
    UUID colleague = createEmployee("Tono Gudang", "3206000000000002", "6666777788880002");
    UUID myContract = addContract(me);
    UUID colleagueContract = addContract(colleague);
    addAssignment(me, outlet, "cashier");
    addAssignment(colleague, outlet, "waiter");
    addCompensation(me, myContract, 400000000L);
    addCompensation(colleague, colleagueContract, 900000000L);
    linkLogin(me, mySub);
    linkLogin(colleague, otherSub);

    UUID runId = runPayroll(List.of(me, colleague));

    // Profile — own data, PII still masked even to myself.
    String profile =
        mvc.perform(
                get("/api/v1/me/profile").header("X-Company-Id", TENANT).header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employeeId").value(me.toString()))
            .andExpect(jsonPath("$.fullName").value("Rina Kasir"))
            .andExpect(jsonPath("$.maskedNik").value("***REDACTED***"))
            .andExpect(jsonPath("$.assignments.length()").value(1))
            .andExpect(jsonPath("$.assignments[0].role").value("cashier"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(profile).doesNotContain("3206000000000001").doesNotContain("6666777788880001");

    // Payslip index — only MY runs, headers only (no amounts on the list).
    String slips =
        mvc.perform(
                get("/api/v1/me/payslips")
                    .param("period", PERIOD)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].runId").value(runId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(slips).doesNotContain("amountMinor");

    // Payslip detail — REAL amounts for MY lines; the colleague's figures never appear.
    String detail =
        mvc.perform(
                get("/api/v1/me/payslips/" + runId)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode detailNode = json.readTree(detail);
    assertThat(detailNode.get("grossMinor").asLong()).isEqualTo(400000000L);
    assertThat(detailNode.get("netMinor").asLong()).isGreaterThan(0L);
    assertThat(detailNode.get("netMinor").asLong()).isLessThan(400000000L);
    boolean sawBase = false;
    for (JsonNode line : detailNode.get("lines")) {
      if ("BASE".equals(line.get("componentKey").asText())) {
        sawBase = true;
        assertThat(line.get("amountMinor").asLong()).isEqualTo(400000000L);
      }
    }
    assertThat(sawBase).isTrue();
    // The colleague's base (900000000) must never leak into my view.
    assertThat(detail).doesNotContain("900000000");
  }

  @Test
  void unlinkedLoginsForeignRunsAndForeignTenantsFailClosed() throws Exception {
    seedIllustrative();
    UUID outlet = seedOrgUnit("outlet");
    String linkedSub = UUID.randomUUID().toString();
    UUID me = createEmployee("Budi Santoso", "3206000000000003", "6666777788880003");
    UUID contract = addContract(me);
    addAssignment(me, outlet, "chef");
    addCompensation(me, contract, 300000000L);
    linkLogin(me, linkedSub);
    UUID runId = runPayroll(List.of(me));

    // An unlinked login → 404 not-linked problem on every /me read.
    String unlinkedSub = UUID.randomUUID().toString();
    mvc.perform(
            get("/api/v1/me/profile").header("X-Company-Id", TENANT).header("X-Actor", unlinkedSub))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/employee-not-linked"));
    mvc.perform(
            get("/api/v1/me/payslips/" + runId)
                .header("X-Company-Id", TENANT)
                .header("X-Actor", unlinkedSub))
        .andExpect(status().isNotFound());

    // A run that exists but carries none of MY lines → 404 (anti-enumeration).
    UUID other = createEmployee("Siti Rahma", "3206000000000004", "6666777788880004");
    UUID otherContract = addContract(other);
    addCompensation(other, otherContract, 200000000L);
    String otherSub = UUID.randomUUID().toString();
    linkLogin(other, otherSub);
    mvc.perform(
            get("/api/v1/me/payslips/" + runId)
                .header("X-Company-Id", TENANT)
                .header("X-Actor", otherSub))
        .andExpect(status().isNotFound());

    // A foreign tenant with the same sub sees nothing (RLS fail-closed).
    mvc.perform(
            get("/api/v1/me/profile")
                .header("X-Company-Id", "22222222-2222-2222-2222-222222222222")
                .header("X-Actor", linkedSub))
        .andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------------------------------------
  // P10 review C1 — findMyPayslipHeaders had NEITHER the POSTED filter NOR the run_seq-supersession
  // filter, so a superseded correction re-run's stale lines summed ALONGSIDE the active run's (the
  // console payslip list AND the YTD card, which sums every listed header's detail, double-counted),
  // and a CALCULATED-but-never-POSTED run's already-persisted lines appeared as if final. The fix
  // filters with the SAME ACTIVE_RUN_PREDICATE the December true-up query family already uses.
  // ---------------------------------------------------------------------------------------------

  @Test
  void payslipHeadersExcludeASupersededRunAndARunThatWasNeverPosted() throws Exception {
    UUID employeeA =
        TenantContext.callAs(
            TENANT,
            HR_ACTOR,
            () -> {
              illustrativeSeeder.seed("IDR");
              UUID outlet = seedOrgUnit("outlet");
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Rara Wulandari", "TK0", "3209300000000001", "3209300000000001"))
                      .getId();
              EmploymentContract contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(10_000_000L, "IDR"),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      employeeId,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              // Two runs of the SAME period: run_seq=1, then run_seq=2 SUPERSEDES it (both POSTED —
              // nothing about the employee changed, only the run_seq differs; the recipe only needs
              // to prove the SET of runs the header list returns, not a materially different figure).
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), "IDR");
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), "IDR");

              // A DIFFERENT period, calculated but deliberately never posted (PayrollRunService#post
              // is never called) — CALCULATED forever, with persisted payslip lines.
              PayrollRun neverPosted =
                  payrollRunService.calculate(
                      new RunPayrollCommand("2026-07", List.of(employeeId), List.of()), "IDR");
              org.assertj.core.api.Assertions.assertThat(neverPosted.getStatus().name())
                  .isEqualTo("CALCULATED");

              return employeeId;
            });

    String subA = UUID.randomUUID().toString();
    linkLogin(employeeA, subA);

    // June: only the run_seq=2 header — the superseded run_seq=1 is GONE.
    String juneSlips =
        mvc.perform(
                get("/api/v1/me/payslips")
                    .param("period", "2026-06")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", subA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].runSeq").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(juneSlips).doesNotContain("\"runSeq\":1");

    // July: the CALCULATED-but-never-POSTED run never appears at all.
    mvc.perform(
            get("/api/v1/me/payslips")
                .param("period", "2026-07")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", subA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // The unscoped (every-period) list also carries exactly ONE header — June's active run only.
    mvc.perform(
            get("/api/v1/me/payslips").header("X-Company-Id", TENANT).header("X-Actor", subA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].period").value("2026-06"))
        .andExpect(jsonPath("$[0].runSeq").value(2));
  }

  // ---------------------------------------------------------------------------------------------
  // The December true-up's NEGATIVE PPh21 line (an over-withheld refund, Track P Phase P3) is a
  // DIFFERENT axis from C1 entirely — it must NOT be excluded by the C1 fix (the negative sign is
  // real data, not a rejected/superseded run). Same golden recipe as
  // PayrollAnnualTrueUpEndToEndTest#aNegativeDecemberPphLineSurvivesTheFullPathAndRoundTripsThroughEncryption
  // (two high-TER months then a low December): -62,665,700.
  // ---------------------------------------------------------------------------------------------

  @Test
  void payslipHeadersIncludeTheNegativeDecemberTrueUpRunUnaffectedByItsSign() throws Exception {
    Map<String, UUID> ids =
        TenantContext.callAs(
            TENANT,
            HR_ACTOR,
            () -> {
              illustrativeSeeder.seed("IDR");
              officialSeeder.seed("ID-2026.1");
              UUID outlet = seedOrgUnit("outlet");
              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Farhan Nugroho", "TK0", "3209300000000002", "3209300000000002"))
                      .getId();
              employeeService.update(
                  new UpdateEmployeeCommand(
                      employeeId, null, null, null, null, "9209300000000002", null, null));
              EmploymentContract contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              var highPkg =
                  compensationWriter.createPackage(
                      employeeId,
                      contract.getId(),
                      Money.ofMinor(300_000_000L, "IDR"),
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      employeeId,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-10", List.of(employeeId), List.of()), "IDR");
              payrollRunService.calculateAndPost(
                  new RunPayrollCommand("2026-11", List.of(employeeId), List.of()), "IDR");

              compensationWriter.endPackage(employeeId, highPkg.getId(), LocalDate.of(2026, 11, 30));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(5_000_000L, "IDR"),
                  LocalDate.of(2026, 12, 1),
                  LocalDate.of(9999, 12, 31));

              UUID decemberRunId =
                  payrollRunService
                      .calculateAndPost(
                          new RunPayrollCommand("2026-12", List.of(employeeId), List.of()), "IDR")
                      .getId();
              return Map.of("employee", employeeId, "december", decemberRunId);
            });

    String sub = UUID.randomUUID().toString();
    linkLogin(ids.get("employee"), sub);

    // All three months are listed — the negative December line does NOT get filtered out.
    mvc.perform(get("/api/v1/me/payslips").header("X-Company-Id", TENANT).header("X-Actor", sub))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].period").value("2026-12")); // newest first

    // The December detail's PPh21 line is the golden negative refund figure — sums correctly into
    // a REDUCED year-to-date total on the client (useMyPayslipsYtd sums every listed run's lines).
    String detail =
        mvc.perform(
                get("/api/v1/me/payslips/" + ids.get("december"))
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", sub))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode detailNode = json.readTree(detail);
    boolean sawNegativePph21 = false;
    for (JsonNode line : detailNode.get("lines")) {
      if ("PPH21".equals(line.get("componentKey").asText())) {
        assertThat(line.get("amountMinor").asLong()).isEqualTo(-62_665_700L);
        sawNegativePph21 = true;
      }
    }
    assertThat(sawNegativePph21).isTrue();
  }

  // ---- helpers --------------------------------------------------------------------------------

  private void seedIllustrative() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk());
  }

  private UUID createEmployee(String name, String nik, String bank) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID addContract(UUID employeeId) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/contracts")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "employmentType", "PERMANENT",
                                "legalEmployerId", LEGAL_EMPLOYER.toString(),
                                "effectiveFrom", "2026-01-01"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private void addAssignment(UUID employeeId, UUID orgUnitId, String role) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "orgUnitId",
                            orgUnitId.toString(),
                            "role",
                            role,
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isCreated());
  }

  private void addCompensation(UUID employeeId, UUID contractId, long basePayMinor)
      throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            contractId.toString(),
                            "basePayMinor",
                            basePayMinor,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isCreated());
  }

  private void linkLogin(UUID employeeId, String sub) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk());
  }

  private UUID runPayroll(List<UUID> employeeIds) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/payroll-runs")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "period",
                                PERIOD,
                                "employeeIds",
                                employeeIds.stream().map(UUID::toString).toList(),
                                "baseCurrency",
                                "IDR"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID seedOrgUnit(String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT, LEGAL_EMPLOYER, type, true));
    return orgUnitId;
  }
}
