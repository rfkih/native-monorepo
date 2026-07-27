package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.withinclose.controller.WithinCompanyCloseController;
import id.co.nativeapp.finance.withinclose.dto.CloseHistoryResponse;
import id.co.nativeapp.finance.withinclose.service.CloseHistoryReader;
import id.co.nativeapp.finance.withinclose.service.WithinCompanyCloseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for {@code GET /api/v1/closes}: a 200 with the close history when records
 * exist, and a 200 with an empty list when no closes have been performed yet. Both service
 * dependencies are mocked — no DB. Mirrors the {@code StatementsControllerTest} style.
 *
 * <p>The reader now returns {@link CloseHistoryResponse} directly (projection-to-DTO mapping in the
 * service layer — CODE-STRUCTURE §3.3), so the mock returns DTO instances.
 */
@WebMvcTest(WithinCompanyCloseController.class)
@Import(ConstraintViolationAdvice.class)
class CloseHistoryControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WithinCompanyCloseService closeService;
  @MockitoBean private CloseHistoryReader closeHistoryReader;

  @Test
  void listClosesReturns200WithCloseHistory() throws Exception {
    CloseHistoryResponse stub =
        new CloseHistoryResponse(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "2026-06", "IDR", true, true, false);

    when(closeHistoryReader.findAllForCurrentTenant()).thenReturn(List.of(stub));

    mockMvc
        .perform(get("/api/v1/closes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].closeId").value("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        .andExpect(jsonPath("$[0].period").value("2026-06"))
        .andExpect(jsonPath("$[0].baseCurrency").value("IDR"))
        .andExpect(jsonPath("$[0].firstClose").value(true))
        .andExpect(jsonPath("$[0].reconciled").value(true))
        .andExpect(jsonPath("$[0].usesIllustrativeRules").value(false));
  }

  @Test
  void listClosesReturns200WithEmptyListWhenNoClosesExist() throws Exception {
    when(closeHistoryReader.findAllForCurrentTenant()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/closes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }
}
