package id.co.nativeapp.loyalty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.loyalty.config.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /healthz} — the lightweight liveness probe. No DB, no tenant header
 * (the probe carries none): the slice boots only the {@link HealthController}.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void healthzReturnsUp() throws Exception {
    mockMvc
        .perform(get("/healthz"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("loyalty-service"));
  }
}
