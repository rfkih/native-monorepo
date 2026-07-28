package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.payroll.dto.MetricProjectedEvent;
import id.co.nativeapp.employee.payroll.service.MetricInputProjectionService;
import id.co.nativeapp.tenant.TenantContext;
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
 * Own-sales commission end-to-end (E3 + E4): a linked employee with a 5% PERCENT_OF_METRIC
 * commission on {@code sales_amount} earns 5% of the sales rung under their own login (metrics at
 * EMPLOYEE grain keyed on their sub). The commission is a taxable EARNING, so it moves gross AND
 * net; the /me payslip shows a real COMMISSION line. An employee with NO linked login earns none.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OwnSalesCommissionTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String HR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final String PERIOD = "2026-07";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private MetricInputProjectionService metricService;
  @Autowired private id.co.nativeapp.employee.org.service.OrgProjectionService orgProjectionService;

  @Test
  void aFivePercentCommissionOnOwnSalesFlowsIntoTheRunAndThePayslip() throws Exception {
    seedIllustrative();
    UUID outlet = seedOutlet();
    String mySub = UUID.randomUUID().toString();

    UUID employeeId = createEmployee("Rina Kasir", "3207000000000001", "7777888899990001");
    UUID contract = addContract(employeeId);
    addAssignment(employeeId, outlet, "cashier");
    UUID packageId = addCompensation(employeeId, contract, 400_000_000L); // base 4,000,000.00
    linkLogin(employeeId, mySub);

    // Set a 5% (500 bp) own-sales commission.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation/" + packageId + "/commission")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("percentBasisPoints", 500, "metricKey", "sales_amount"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.percentBasisPoints").value(500))
        .andExpect(jsonPath("$.metricKey").value("sales_amount"));

    // A duplicate open commission on the same metric → 409.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation/" + packageId + "/commission")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("percentBasisPoints", 300, "metricKey", "sales_amount"))))
        .andExpect(status().isConflict());

    // Project the employee's own sales: three daily rows summing to 20,000,000.00 (subject = sub).
    projectSales(mySub, "2026-07-03", 800_000_000L);
    projectSales(mySub, "2026-07-10", 700_000_000L);
    projectSales(mySub, "2026-07-20", 500_000_000L);

    UUID runId = runPayroll(List.of(employeeId));

    // Gross = base 4,000,000.00 + 5% of 20,000,000.00 (= 1,000,000.00) = 5,000,000.00.
    String detail =
        mvc.perform(
                get("/api/v1/me/payslips/" + runId)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode node = json.readTree(detail);
    assertThat(node.get("grossMinor").asLong()).isEqualTo(500_000_000L);
    boolean sawCommission = false;
    for (JsonNode line : node.get("lines")) {
      if ("COMMISSION".equals(line.get("componentKey").asText())) {
        sawCommission = true;
        assertThat(line.get("amountMinor").asLong()).isEqualTo(100_000_000L); // 5% of 20M
      }
    }
    assertThat(sawCommission).isTrue();
    // Taxable: net is below gross (the commission moved PPh21), and positive.
    assertThat(node.get("netMinor").asLong()).isLessThan(500_000_000L);
    assertThat(node.get("netMinor").asLong()).isGreaterThan(0L);
  }

  @Test
  void anUnlinkedEmployeeWithACommissionRuleEarnsZeroCommission() throws Exception {
    seedIllustrative();
    UUID outlet = seedOutlet();
    UUID employeeId = createEmployee("Tono Gudang", "3207000000000002", "7777888899990002");
    UUID contract = addContract(employeeId);
    addAssignment(employeeId, outlet, "waiter");
    UUID packageId = addCompensation(employeeId, contract, 300_000_000L);

    // Commission configured, but the employee has NO linked login → no own-sales metrics resolve.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation/" + packageId + "/commission")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("percentBasisPoints", 500, "metricKey", "sales_amount"))))
        .andExpect(status().isCreated());

    UUID runId = runPayroll(List.of(employeeId));
    // Gross = base only (no commission), because the unlinked employee resolves no metrics.
    String run =
        mvc.perform(
                get("/api/v1/payroll-runs/" + runId)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(run).get("grossTotalMinor").asLong()).isEqualTo(300_000_000L);
  }

  @Test
  void aCommissionWithNoRateIs400NotAnUnhandled500() throws Exception {
    seedIllustrative();
    UUID outlet = seedOutlet();
    UUID employeeId = createEmployee("Dewi Null", "3207000000000003", "7777888899990003");
    UUID contract = addContract(employeeId);
    addAssignment(employeeId, outlet, "cashier");
    UUID packageId = addCompensation(employeeId, contract, 300_000_000L);

    // percentBasisPoints omitted (null) → @NotNull rejects at the edge (400), never a 500 NPE from
    // unboxing null into the primitive-int service parameter.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation/" + packageId + "/commission")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("metricKey", "sales_amount"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listingCommissionsOnAPackageThatIsNotTheEmployeesIs404() throws Exception {
    seedIllustrative();
    UUID outlet = seedOutlet();

    UUID employeeA = createEmployee("Ana A", "3207000000000004", "7777888899990004");
    UUID contractA = addContract(employeeA);
    addAssignment(employeeA, outlet, "cashier");
    addCompensation(employeeA, contractA, 300_000_000L);

    UUID employeeB = createEmployee("Beno B", "3207000000000005", "7777888899990005");
    UUID contractB = addContract(employeeB);
    addAssignment(employeeB, outlet, "cashier");
    UUID packageB = addCompensation(employeeB, contractB, 300_000_000L);

    // Employee A's path + Employee B's package → 404 (the package is not A's); it must NOT return
    // B's commission rules just because A exists (the write path already guards via
    // requireOwnPackage).
    mvc.perform(
            get("/api/v1/employees/" + employeeA + "/compensation/" + packageB + "/commission")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR))
        .andExpect(status().isNotFound());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private void projectSales(String sub, String period, long minor) {
    TenantContext.runAs(
        TENANT,
        "sales-metric",
        () ->
            metricService.apply(
                new MetricProjectedEvent(
                    UUID.randomUUID(),
                    TENANT,
                    "sales_amount",
                    period,
                    "employee",
                    UUID.fromString(sub),
                    minor)));
  }

  private void seedIllustrative() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk());
  }

  private UUID createEmployee(String name, String nik, String bank) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "fullName", name,
                                "ptkpStatus", "TK0",
                                "nik", nik,
                                "bankAccount", bank))))
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
                    .header("X-Actor", HR)
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
                .header("X-Actor", HR)
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

  private UUID addCompensation(UUID employeeId, UUID contractId, long basePayMinor)
      throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/compensation")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR)
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
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private void linkLogin(UUID employeeId, String sub) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk());
  }

  private UUID runPayroll(List<UUID> employeeIds) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/payroll-runs")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR)
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

  private UUID seedOutlet() {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT, LEGAL_EMPLOYER, "outlet", true));
    return orgUnitId;
  }
}
