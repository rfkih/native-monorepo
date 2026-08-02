package id.co.nativeapp.employee.timeoff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.controller.MyLeaveBalanceController;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveBalanceReader;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-slice test for {@link MyLeaveBalanceController}: the caller's own derived balance read. */
@WebMvcTest(MyLeaveBalanceController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class MyLeaveBalanceControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LeaveBalanceReader balanceReader;

  @Test
  void getReturns200WithTheDerivedBalance() throws Exception {
    when(balanceReader.myBalance(any())).thenReturn(LeaveBalanceResponse.of(2026, 12, 0, 3));

    mockMvc
        .perform(get("/api/v1/me/leave-balance"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.year").value(2026))
        .andExpect(jsonPath("$.grantedDays").value(12))
        .andExpect(jsonPath("$.usedDays").value(3))
        .andExpect(jsonPath("$.remaining").value(9));
  }

  @Test
  void getPassesTheYearQueryParamThrough() throws Exception {
    when(balanceReader.myBalance(eq(2025))).thenReturn(LeaveBalanceResponse.of(2025, 12, 0, 12));

    mockMvc
        .perform(get("/api/v1/me/leave-balance?year=2025"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.year").value(2025))
        .andExpect(jsonPath("$.remaining").value(0));
  }

  @Test
  void getWhenTheCallerHasNoEmployeeLinkIs404() throws Exception {
    when(balanceReader.myBalance(any())).thenThrow(new EmployeeNotLinkedException());

    mockMvc
        .perform(get("/api/v1/me/leave-balance"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/employee-not-linked"));
  }
}
