package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HTTP-boundary coverage for {@code /api/v1/employees/{eid}/compensation} — base pay is salary PII
 * (rule 6): the write accepts a plaintext minor amount, but EVERY read is masked ({@code "***"}) —
 * the raw response body must contain no numeric amount at all, and the list read path never even
 * selects the {@code base_pay_enc} ciphertext. Overlapping packages are rejected (the payroll run
 * SUMS all covering packages — an overlap would silently double-pay). Replace = end the old package
 * + create the new one (effective-dated, like every Native row).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class CompensationEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final long BASE_PAY_MINOR = 750000000L; // 7,500,000.00 IDR — must never echo

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;

  @Test
  void createReadEndAndReplaceAreMaskedAndOverlapRejected() throws Exception {
    UUID employeeId = createEmployee("Budi Santoso", "3203000000000001", "3333444455550001");
    UUID contractId = addContract(employeeId);

    // Create — the response is masked and carries NO numeric amount anywhere.
    String created =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/compensation")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "employmentContractId",
                                contractId.toString(),
                                "basePayMinor",
                                BASE_PAY_MINOR,
                                "currency",
                                "IDR",
                                "effectiveFrom",
                                "2026-01-01"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amountMasked").value("***"))
            .andExpect(jsonPath("$.effectiveTo").value("9999-12-31"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(created).doesNotContain(String.valueOf(BASE_PAY_MINOR)).doesNotContain("7500000");
    UUID packageId = UUID.fromString(json.readTree(created).get("id").asText());

    // Read — masked list, no amount digits, no basePay key.
    String list =
        mvc.perform(
                get("/api/v1/employees/" + employeeId + "/compensation")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].amountMasked").value("***"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list)
        .doesNotContain(String.valueOf(BASE_PAY_MINOR))
        .doesNotContain("7500000")
        .doesNotContain("basePay");

    // An overlapping second package → 409 (the run SUMS covering packages — double-pay guard).
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            contractId.toString(),
                            "basePayMinor",
                            800000000L,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-06-01"))))
        .andExpect(status().isConflict());

    // Replace = end the old package, then create the successor from the next day.
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/compensation/" + packageId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-07-31"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveTo").value("2026-07-31"))
        .andExpect(jsonPath("$.amountMasked").value("***"));
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            contractId.toString(),
                            "basePayMinor",
                            800000000L,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-08-01"))))
        .andExpect(status().isCreated());

    // Ending an already-ended package → 409.
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/compensation/" + packageId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-09-30"))))
        .andExpect(status().isConflict());
  }

  @Test
  void guardsUnknownEmployeeForeignContractAndCrossTenantReads() throws Exception {
    UUID employeeId = createEmployee("Siti Rahma", "3203000000000002", "3333444455550002");
    UUID otherEmployeeId = createEmployee("Andi Wijaya", "3203000000000003", "3333444455550003");
    UUID otherContractId = addContract(otherEmployeeId);

    // A contract belonging to ANOTHER employee → 400.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            otherContractId.toString(),
                            "basePayMinor",
                            100000000L,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isBadRequest());

    // An unknown employee → 404.
    mvc.perform(
            post("/api/v1/employees/" + UUID.randomUUID() + "/compensation")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            otherContractId.toString(),
                            "basePayMinor",
                            100000000L,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isNotFound());

    // Cross-tenant: another tenant cannot read the employee's packages (404 — the employee
    // itself is invisible under RLS).
    mvc.perform(
            get("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", "44444444-4444-4444-4444-444444444444")
                .header("X-Actor", ACTOR))
        .andExpect(status().isNotFound());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private UUID createEmployee(String name, String nik, String bank) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID addContract(UUID employeeId) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "employmentType", "PERMANENT",
                "legalEmployerId", LEGAL_EMPLOYER.toString(),
                "effectiveFrom", "2026-01-01"));
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/contracts")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }
}
