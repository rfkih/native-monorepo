package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * HTTP-boundary coverage for the employee↔login link ({@code POST/DELETE
 * /api/v1/employees/{id}/login-link}) — the join the /me self-service surface and own-sales
 * commission run on. One login maps to at most ONE employee per company (409 otherwise); the link
 * is service-local (no event) and RLS-scoped (a foreign employee is a 404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class EmployeeLoginLinkEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;

  @Test
  void linksUnlinksAndRejectsADuplicateLoginAcrossEmployees() throws Exception {
    UUID e1 = createEmployee(TENANT_A, "Budi Santoso", "3205000000000001", "5555666677770001");
    UUID e2 = createEmployee(TENANT_A, "Siti Rahma", "3205000000000002", "5555666677770002");
    String sub = UUID.randomUUID().toString();

    // Link → the response and subsequent reads carry the userId.
    mvc.perform(
            post("/api/v1/employees/" + e1 + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(sub));
    mvc.perform(
            get("/api/v1/employees/" + e1)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.employee.userId").value(sub));

    // Relinking the SAME user to the same employee is idempotent.
    mvc.perform(
            post("/api/v1/employees/" + e1 + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk());

    // The same login on a DIFFERENT employee → 409 (one login = at most one employee).
    mvc.perform(
            post("/api/v1/employees/" + e2 + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isConflict());

    // Unlink frees the login; the other employee can then take it.
    mvc.perform(
            delete("/api/v1/employees/" + e1 + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").isEmpty());
    mvc.perform(
            post("/api/v1/employees/" + e2 + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk());
  }

  @Test
  void guardsUnknownEmployeesAndCrossTenantAccess() throws Exception {
    UUID employeeId =
        createEmployee(TENANT_A, "Andi Wijaya", "3205000000000003", "5555666677770003");

    // Unknown employee → 404.
    mvc.perform(
            post("/api/v1/employees/" + UUID.randomUUID() + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", UUID.randomUUID().toString()))))
        .andExpect(status().isNotFound());

    // A cross-tenant caller cannot see the employee → 404 (RLS fail-closed).
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", UUID.randomUUID().toString()))))
        .andExpect(status().isNotFound());

    // A blank userId → 400.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", " "))))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private UUID createEmployee(String tenant, String name, String nik, String bank)
      throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", tenant)
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
