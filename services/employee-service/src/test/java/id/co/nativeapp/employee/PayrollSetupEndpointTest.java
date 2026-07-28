package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for {@code /api/v1/payroll-setup} — the console's one-click payroll
 * bootstrap. GET reports whether the tenant's pay-component catalog + statutory rules exist and
 * their provenance; POST seeds the ILLUSTRATIVE PLACEHOLDER set (delegating to the existing
 * idempotent {@code IllustrativeStatutorySeedWriter} — the V3 banner and {@code
 * IllustrativeSeedBannerDriftTest} stay untouched). RLS scopes both: one tenant's seed is invisible
 * to another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class PayrollSetupEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;

  @Test
  void seedIllustrativeIsIdempotentAndRlsScoped() throws Exception {
    // Before: nothing seeded.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false))
        .andExpect(jsonPath("$.componentCount").value(0))
        .andExpect(jsonPath("$.provenance").isEmpty())
        .andExpect(jsonPath("$.illustrativeVersion").isEmpty());

    // Seed.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(true))
        .andExpect(jsonPath("$.componentCount").value(6))
        .andExpect(jsonPath("$.provenance").value("ILLUSTRATIVE_PLACEHOLDER"))
        .andExpect(jsonPath("$.illustrativeVersion").value("ILLUSTRATIVE-2026.1"));

    // Idempotent: a second POST changes nothing.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentCount").value(6));

    // RLS: tenant B still sees nothing.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_B).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false));
  }

  @Test
  void seedWithoutABaseCurrencyIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
