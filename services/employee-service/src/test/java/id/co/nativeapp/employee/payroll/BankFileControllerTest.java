package id.co.nativeapp.employee.payroll;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.payroll.controller.BankFileController;
import id.co.nativeapp.employee.payroll.domain.PayrollRunNotFoundException;
import id.co.nativeapp.employee.payroll.domain.PayrollRunNotPostedException;
import id.co.nativeapp.employee.payroll.service.BankFileReader;
import id.co.nativeapp.employee.payroll.service.BankFileResult;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link BankFileController} (Track P phase P5): the CSV happy path
 * (content-type, filename, body), 404 (unknown run), and 409 (not POSTED). {@link BankFileReader}
 * mocked — the PII-decryption/audit-log behaviour is covered by {@code BankFileReaderTest}
 * (Testcontainers).
 */
@WebMvcTest(BankFileController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class BankFileControllerTest {

  private static final UUID RUN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BankFileReader bankFileReader;

  @Test
  void bankFileReturnsCsvWithTheExpectedContentTypeAndFilename() throws Exception {
    String csv =
        "employee_name,bank_account,net_amount_minor,currency,reference\nBudi,****3456,17798333,IDR,PAYROLL-2026-06-1\n# row_count=1\n";
    when(bankFileReader.generate(RUN)).thenReturn(new BankFileResult(csv, "2026-06", 1));

    mockMvc
        .perform(get("/api/v1/payroll-runs/{runId}/bank-file", RUN))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("payroll-2026-06-seq1.csv")))
        .andExpect(content().string(csv));
  }

  @Test
  void unknownRunIsA404() throws Exception {
    when(bankFileReader.generate(RUN)).thenThrow(new PayrollRunNotFoundException(RUN));

    mockMvc
        .perform(get("/api/v1/payroll-runs/{runId}/bank-file", RUN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/payroll-run-not-found"));
  }

  @Test
  void aNonPostedRunIsA409() throws Exception {
    when(bankFileReader.generate(RUN))
        .thenThrow(new PayrollRunNotPostedException(RUN, "CALCULATED"));

    mockMvc
        .perform(get("/api/v1/payroll-runs/{runId}/bank-file", RUN))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/payroll-run-not-posted"));
  }
}
