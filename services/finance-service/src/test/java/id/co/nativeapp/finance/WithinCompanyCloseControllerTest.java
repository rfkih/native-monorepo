package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.withinclose.controller.WithinCompanyCloseController;
import id.co.nativeapp.finance.withinclose.domain.MultiCurrencyTrialBalanceException;
import id.co.nativeapp.finance.withinclose.dto.WithinCompanyCloseResult;
import id.co.nativeapp.finance.withinclose.service.WithinCompanyCloseService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the P3d SEAM 4a producer surface — the HTTP contract of {@link
 * WithinCompanyCloseController} ({@code POST /api/v1/closes}): a {@code 200} with the close outcome
 * on a first close, a {@code 200} with {@code firstClose=false} on an idempotent re-close, a typed
 * {@code 422} (RFC-7807) when the period's ledger spans multiple currencies, and a {@code 400}
 * (RFC-7807) for a malformed period. The {@link WithinCompanyCloseService} is mocked — no DB, no
 * RLS, no event transport: this proves only that the controller maps the service outcome / faults
 * to the right status + body. The fault mappings come from the imported advices ({@link
 * ConstraintViolationAdvice} owns the within-close typed {@code 422}s; the shared {@link
 * ApiExceptionHandler} owns the {@code @Valid}-body {@code 400}).
 */
@WebMvcTest(WithinCompanyCloseController.class)
@Import({ConstraintViolationAdvice.class, ApiExceptionHandler.class})
class WithinCompanyCloseControllerTest {

  private static final UUID CLOSE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID GROUP = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WithinCompanyCloseService closeService;

  @Test
  void aFirstCloseReturns200WithTheOutcomeAndPublishedGroups() throws Exception {
    when(closeService.close(eq("2026-06"), eq("IDR")))
        .thenReturn(
            new WithinCompanyCloseResult(CLOSE_ID, "2026-06", true, true, false, List.of(GROUP)));

    mockMvc
        .perform(
            post("/api/v1/closes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-06\",\"baseCurrency\":\"IDR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closeId").value(CLOSE_ID.toString()))
        .andExpect(jsonPath("$.period").value("2026-06"))
        .andExpect(jsonPath("$.firstClose").value(true))
        .andExpect(jsonPath("$.reconciled").value(true))
        .andExpect(jsonPath("$.usesIllustrativeRules").value(false))
        .andExpect(jsonPath("$.trialBalancePublishedGroups[0]").value(GROUP.toString()));
  }

  @Test
  void anIdempotentReCloseReturns200WithFirstCloseFalseAndNoGroups() throws Exception {
    // The optional baseCurrency cross-check is absent — the body is just the period.
    when(closeService.close(eq("2026-06"), isNull()))
        .thenReturn(
            new WithinCompanyCloseResult(CLOSE_ID, "2026-06", false, true, false, List.of()));

    mockMvc
        .perform(
            post("/api/v1/closes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-06\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstClose").value(false))
        .andExpect(jsonPath("$.trialBalancePublishedGroups").isEmpty());
  }

  @Test
  void aMultiCurrencyLedgerMapsToAProblemDetail422() throws Exception {
    when(closeService.close(eq("2026-06"), eq("IDR")))
        .thenThrow(new MultiCurrencyTrialBalanceException("2026-06", "IDR", "USD"));

    mockMvc
        .perform(
            post("/api/v1/closes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-06\",\"baseCurrency\":\"IDR\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/multi-currency-trial-balance"));
  }

  @Test
  void aMalformedPeriodIsAProblemDetail400() throws Exception {
    // 2026-13 is an impossible month; the @Valid @Pattern on the body rejects it before the
    // service.
    mockMvc
        .perform(
            post("/api/v1/closes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"2026-13\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
