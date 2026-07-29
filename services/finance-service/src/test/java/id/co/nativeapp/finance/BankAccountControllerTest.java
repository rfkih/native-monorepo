package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.bank.controller.BankAccountController;
import id.co.nativeapp.finance.bank.controller.BankAdvice;
import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.service.BankAccountReader;
import id.co.nativeapp.finance.bank.service.BankAccountWriter;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import id.co.nativeapp.finance.bank.service.StatementLineWriter;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link BankAccountController}: a 201 on create, a 404 for an unknown bank
 * account, and a 400 for a blank name / invalid currency. Services mocked; fault mappings from
 * {@link BankAdvice} + the shared {@link ApiExceptionHandler}. Mirrors {@link
 * VendorControllerTest}.
 */
@WebMvcTest(BankAccountController.class)
@Import({BankAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class BankAccountControllerTest {

  private static final UUID BANK_ACCOUNT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BankAccountWriter bankAccountWriter;
  @MockitoBean private BankAccountReader bankAccountReader;
  @MockitoBean private StatementLineWriter statementLineWriter;
  @MockitoBean private StatementLineReader statementLineReader;

  @Test
  void createReturns201() throws Exception {
    when(bankAccountWriter.create(any(), any(), any()))
        .thenReturn(
            new BankAccountResponse(BANK_ACCOUNT, "BCA Operating", "1234567890", "IDR", true));

    mockMvc
        .perform(
            post("/api/v1/bank-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"BCA Operating\",\"accountNumber\":\"1234567890\","
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(BANK_ACCOUNT.toString()))
        .andExpect(jsonPath("$.name").value("BCA Operating"))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void getUnknownBankAccountIsAProblemDetail404() throws Exception {
    when(bankAccountReader.get(BANK_ACCOUNT))
        .thenThrow(new BankAccountNotFoundException(BANK_ACCOUNT));

    mockMvc
        .perform(get("/api/v1/bank-accounts/{id}", BANK_ACCOUNT))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bank-not-found"));
  }

  @Test
  void createWithBlankNameIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \",\"currency\":\"IDR\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void createWithInvalidCurrencyIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"BCA\",\"currency\":\"idr\"}"))
        .andExpect(status().isBadRequest());
  }
}
