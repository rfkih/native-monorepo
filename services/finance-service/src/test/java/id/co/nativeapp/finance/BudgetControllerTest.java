package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.budget.controller.BudgetAdvice;
import id.co.nativeapp.finance.budget.controller.BudgetController;
import id.co.nativeapp.finance.budget.domain.BudgetNotFoundException;
import id.co.nativeapp.finance.budget.domain.UnknownAccountException;
import id.co.nativeapp.finance.budget.dto.BudgetActualsResponse;
import id.co.nativeapp.finance.budget.dto.BudgetResponse;
import id.co.nativeapp.finance.budget.dto.BudgetResponse.BudgetLineDto;
import id.co.nativeapp.finance.budget.service.AccountCatalogReader;
import id.co.nativeapp.finance.budget.service.BudgetActualReader;
import id.co.nativeapp.finance.budget.service.BudgetReader;
import id.co.nativeapp.finance.budget.service.BudgetWriter;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link BudgetController}: create (201), list (200), detail (200), actuals
 * (200), delete (204), and the fault mappings from {@link BudgetAdvice} + the shared {@link
 * ApiExceptionHandler} — 400 (blank name / unknown account), 404 (unknown budget), 409 (duplicate).
 * Services mocked. Mirrors {@code BillControllerTest} / {@code TaxControllerTest}.
 */
@WebMvcTest(BudgetController.class)
@Import({BudgetAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class BudgetControllerTest {

  private static final UUID BUDGET = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000009");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BudgetWriter budgetWriter;
  @MockitoBean private BudgetReader budgetReader;
  @MockitoBean private BudgetActualReader budgetActualReader;
  @MockitoBean private AccountCatalogReader accountCatalogReader;

  private static final String CREATE_BODY =
      "{\"name\":\"Q3 plan\",\"period\":\"2026-07\",\"currency\":\"IDR\","
          + "\"lines\":[{\"accountCode\":\"4000\",\"amountMinor\":10000000}]}";

  private static BudgetResponse sampleBudget() {
    return new BudgetResponse(
        BUDGET,
        "Q3 plan",
        "2026-07",
        "IDR",
        List.of(new BudgetLineDto("4000", "Sales Revenue", "REVENUE", 10_000_000L)));
  }

  @Test
  void createReturns201WithDetail() throws Exception {
    when(budgetWriter.create(eq("Q3 plan"), eq("2026-07"), eq("IDR"), any())).thenReturn(BUDGET);
    when(budgetReader.detail(BUDGET)).thenReturn(sampleBudget());

    mockMvc
        .perform(
            post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Q3 plan"))
        .andExpect(jsonPath("$.lines[0].accountCode").value("4000"))
        .andExpect(jsonPath("$.lines[0].amountMinor").value(10_000_000L));
  }

  @Test
  void createWithBlankNameIsA400() throws Exception {
    String body =
        "{\"name\":\"\",\"period\":\"2026-07\",\"currency\":\"IDR\","
            + "\"lines\":[{\"accountCode\":\"4000\",\"amountMinor\":1}]}";
    mockMvc
        .perform(post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithAnUnknownAccountIsA400() throws Exception {
    when(budgetWriter.create(any(), any(), any(), any()))
        .thenThrow(new UnknownAccountException("9998"));

    mockMvc
        .perform(
            post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/budget-unknown-account"));
  }

  @Test
  void createADuplicateBudgetIsA409() throws Exception {
    when(budgetWriter.create(any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("uq_budget_company_name_period"));

    mockMvc
        .perform(
            post("/api/v1/budgets").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/budget-conflict"));
  }

  @Test
  void listReturns200() throws Exception {
    when(budgetReader.list()).thenReturn(List.of());
    mockMvc.perform(get("/api/v1/budgets")).andExpect(status().isOk());
  }

  @Test
  void detailReturns200() throws Exception {
    when(budgetReader.detail(BUDGET)).thenReturn(sampleBudget());
    mockMvc
        .perform(get("/api/v1/budgets/{id}", BUDGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-07"));
  }

  @Test
  void unknownBudgetIsA404() throws Exception {
    when(budgetReader.detail(any())).thenThrow(new BudgetNotFoundException(BUDGET));
    mockMvc
        .perform(get("/api/v1/budgets/{id}", BUDGET))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/budget-not-found"));
  }

  @Test
  void actualsReturns200WithVariance() throws Exception {
    when(budgetActualReader.actuals(BUDGET))
        .thenReturn(
            new BudgetActualsResponse(
                BUDGET,
                "Q3 plan",
                "2026-07",
                "IDR",
                List.of(
                    new BudgetActualsResponse.ActualLine(
                        "4000", "Sales Revenue", "REVENUE", 10_000_000L, 8_000_000L, -2_000_000L)),
                10_000_000L,
                8_000_000L,
                -2_000_000L,
                false));

    mockMvc
        .perform(get("/api/v1/budgets/{id}/actuals", BUDGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalPlannedMinor").value(10_000_000L))
        .andExpect(jsonPath("$.totalActualMinor").value(8_000_000L))
        .andExpect(jsonPath("$.totalVarianceMinor").value(-2_000_000L))
        .andExpect(jsonPath("$.lines[0].varianceMinor").value(-2_000_000L));
  }

  @Test
  void actualsWithACurrencyMismatchIsA422() throws Exception {
    when(budgetActualReader.actuals(BUDGET))
        .thenThrow(new GlMultiCurrencyException("budget " + BUDGET, "USD", "IDR"));

    mockMvc
        .perform(get("/api/v1/budgets/{id}/actuals", BUDGET))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/budget-currency-mismatch"));
  }

  @Test
  void deleteReturns204() throws Exception {
    mockMvc.perform(delete("/api/v1/budgets/{id}", BUDGET)).andExpect(status().isNoContent());
  }

  @Test
  void accountsReturns200() throws Exception {
    when(accountCatalogReader.list()).thenReturn(List.of());
    mockMvc.perform(get("/api/v1/budgets/accounts")).andExpect(status().isOk());
  }
}
