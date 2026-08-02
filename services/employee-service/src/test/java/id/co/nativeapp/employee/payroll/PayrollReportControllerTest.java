package id.co.nativeapp.employee.payroll;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.payroll.controller.PayrollReportController;
import id.co.nativeapp.employee.payroll.service.PayrollReportReader;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link PayrollReportController} (Track P phase P9): the CSV happy path
 * (content-type + filename) for all three reports, and the {@code year}/{@code period} path-param
 * validation (400 on a malformed value). {@link PayrollReportReader} mocked — the
 * decrypt/aggregate/supersession/formula-injection behaviour is covered by {@code
 * PayrollReportReaderTest} (Testcontainers). Role gating (owner-only for {@code 1721a1}/{@code
 * bpjs-summary}) is a GATEWAY concern, proven in {@code GatewayRoleRoutingTest} — this controller
 * carries no server-side role check, mirroring {@code BankFileControllerTest}.
 */
@WebMvcTest(PayrollReportController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class PayrollReportControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PayrollReportReader payrollReportReader;

  @Test
  void bukti1721A1ReturnsCsvWithTheExpectedContentTypeAndFilename() throws Exception {
    String csv = "# comment\nnik,npwp,full_name\n1234,5678,Budi\n# row_count=1\n";
    when(payrollReportReader.bukti1721A1(eq("2026"))).thenReturn(csv);

    mockMvc
        .perform(get("/api/v1/payroll-reports/1721a1").param("year", "2026"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("1721-A1_2026.csv")))
        .andExpect(content().string(csv));
  }

  @Test
  void bukti1721A1RejectsAMalformedYearWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/payroll-reports/1721a1").param("year", "not-a-year"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void pph21MonthlyReturnsCsvWithTheExpectedContentTypeAndFilename() throws Exception {
    String csv = "# comment\nperiod,headcount\n2026-07,2\n";
    when(payrollReportReader.pph21Monthly(eq("2026-07"))).thenReturn(csv);

    mockMvc
        .perform(get("/api/v1/payroll-reports/pph21-monthly").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("pph21-monthly-2026-07.csv")))
        .andExpect(content().string(csv));
  }

  @Test
  void pph21MonthlyRejectsAMalformedPeriodWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/payroll-reports/pph21-monthly").param("period", "2026-13"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bpjsSummaryReturnsCsvWithTheExpectedContentTypeAndFilename() throws Exception {
    String csv = "# comment\nemployee_name,program\nBudi,KESEHATAN\n";
    when(payrollReportReader.bpjsSummary(eq("2026-07"))).thenReturn(csv);

    mockMvc
        .perform(get("/api/v1/payroll-reports/bpjs-summary").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("bpjs-summary-2026-07.csv")))
        .andExpect(content().string(csv));
  }

  @Test
  void bpjsSummaryRejectsAMalformedPeriodWith400() throws Exception {
    mockMvc
        .perform(get("/api/v1/payroll-reports/bpjs-summary").param("period", "bad"))
        .andExpect(status().isBadRequest());
  }
}
