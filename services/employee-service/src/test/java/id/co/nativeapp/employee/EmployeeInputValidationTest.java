package id.co.nativeapp.employee;

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
 * Input sanity at the HTTP edge: every malformed user input is a clean RFC-7807 {@code 400} with a
 * machine-readable errors list — never a 500, and (rule 6) a malformed NIK/bank account is rejected
 * BEFORE it would be sealed into ciphertext. Whitelists: NIK exactly 16 digits, bank account 6–32
 * digits, PTKP TK0–TK3/K0–K3, employment type from the enum, role ≤128 chars.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class EmployeeInputValidationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;

  @Test
  void malformedEmployeeFieldsAreRejectedWith400() throws Exception {
    // NIK too short (15 digits).
    postEmployee("Budi", "TK0", "320100000000001", "12345678").andExpect(status().isBadRequest());
    // NIK with letters.
    postEmployee("Budi", "TK0", "32010000000000AB", "12345678").andExpect(status().isBadRequest());
    // Bank account with letters.
    postEmployee("Budi", "TK0", "3201000000000001", "12AB5678").andExpect(status().isBadRequest());
    // Bank account too short (5 digits).
    postEmployee("Budi", "TK0", "3201000000000001", "12345").andExpect(status().isBadRequest());
    // Unknown PTKP code.
    postEmployee("Budi", "TK9", "3201000000000001", "12345678").andExpect(status().isBadRequest());
    // Name over 255 chars.
    postEmployee("x".repeat(256), "TK0", "3201000000000001", "12345678")
        .andExpect(status().isBadRequest());

    // The valid shape still creates (the whitelist is not over-tight).
    postEmployee("Budi Santoso", "TK0", "3201000000000001", "12345678")
        .andExpect(status().isCreated());
  }

  @Test
  void malformedContractAndAssignmentFieldsAreRejectedWith400() throws Exception {
    String created =
        postEmployee("Siti Rahma", "K1", "3201000000000002", "87654321")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID employeeId = UUID.fromString(json.readTree(created).get("id").asText());

    // Unknown employment type.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/contracts")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentType", "FREELANCE",
                            "legalEmployerId", UUID.randomUUID().toString(),
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").exists());

    // Role over 128 chars.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "orgUnitId", UUID.randomUUID().toString(),
                            "role", "r".repeat(129),
                            "effectiveFrom", "2026-01-01"))))
        .andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.ResultActions postEmployee(
      String name, String ptkp, String nik, String bank) throws Exception {
    return mvc.perform(
        post("/api/v1/employees")
            .header("X-Company-Id", TENANT)
            .header("X-Actor", ACTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "fullName", name,
                        "ptkpStatus", ptkp,
                        "nik", nik,
                        "bankAccount", bank))));
  }
}
