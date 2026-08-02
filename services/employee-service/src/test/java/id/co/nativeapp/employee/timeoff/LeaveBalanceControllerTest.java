package id.co.nativeapp.employee.timeoff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.timeoff.controller.LeaveBalanceController;
import id.co.nativeapp.employee.timeoff.domain.LeaveBalance;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceRowResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceWriter;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link LeaveBalanceController}: the manager list + the adjustment PATCH,
 * including the 404 when the target employee is not visible in the bound tenant.
 */
@WebMvcTest(LeaveBalanceController.class)
@Import({
  EmployeeApiAdvice.class,
  ApiExceptionHandler.class,
  LeaveBalanceControllerTest.FixedClockConfig.class
})
class LeaveBalanceControllerTest {

  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LeaveBalanceReader balanceReader;
  @MockitoBean private LeaveBalanceWriter balanceWriter;

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(balanceReader.forManager(any(), any(), any()))
        .thenReturn(PageResponse.of(List.of(), 0, 50, 0));

    mockMvc
        .perform(get("/api/v1/leave-balances"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }

  @Test
  void adjustReturns200WithTheUpdatedRow() throws Exception {
    when(balanceWriter.adjust(EMPLOYEE, 2026, -2)).thenReturn(new LeaveBalance(EMPLOYEE, 2026));
    when(balanceReader.forEmployee(EMPLOYEE, 2026))
        .thenReturn(LeaveBalanceRowResponse.of(EMPLOYEE, "Budi", 2026, 12, -2, 3));

    mockMvc
        .perform(
            patch("/api/v1/leave-balances/" + EMPLOYEE + "?year=2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adjustmentDays\":-2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.adjustmentDays").value(-2))
        .andExpect(jsonPath("$.remaining").value(7));
  }

  @Test
  void adjustAnUnknownEmployeeIs404() throws Exception {
    when(balanceWriter.adjust(EMPLOYEE, 2026, 1))
        .thenThrow(new EmployeeNotFoundException(EMPLOYEE));

    mockMvc
        .perform(
            patch("/api/v1/leave-balances/" + EMPLOYEE + "?year=2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adjustmentDays\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/employee-not-found"));
  }

  @Test
  void adjustWithAMissingBodyFieldIs400() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/leave-balances/" + EMPLOYEE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
