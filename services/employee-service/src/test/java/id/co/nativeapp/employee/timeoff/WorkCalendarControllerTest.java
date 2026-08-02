package id.co.nativeapp.employee.timeoff;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.timeoff.controller.WorkCalendarController;
import id.co.nativeapp.employee.timeoff.domain.WorkCalendar;
import id.co.nativeapp.employee.timeoff.dto.WorkCalendarResponse;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarReader;
import id.co.nativeapp.employee.timeoff.service.WorkCalendarWriter;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-slice test for {@link WorkCalendarController}: the seed-on-first-read GET and PUT upsert. */
@WebMvcTest(WorkCalendarController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class WorkCalendarControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WorkCalendarReader calendarReader;
  @MockitoBean private WorkCalendarWriter calendarWriter;

  @Test
  void getReturns200WithTheDefaultRow() throws Exception {
    when(calendarReader.get()).thenReturn(new WorkCalendarResponse(5, 21));

    mockMvc
        .perform(get("/api/v1/work-calendar"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.daysPerWeek").value(5))
        .andExpect(jsonPath("$.monthlyDivisor").value(21));
  }

  @Test
  void putUpsertsAndReturns200() throws Exception {
    when(calendarWriter.upsert(6, 25)).thenReturn(new WorkCalendar(6, 25));

    mockMvc
        .perform(
            put("/api/v1/work-calendar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daysPerWeek\":6,\"monthlyDivisor\":25}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.daysPerWeek").value(6))
        .andExpect(jsonPath("$.monthlyDivisor").value(25));
  }

  @Test
  void putWithAnInvalidDaysPerWeekIs400() throws Exception {
    when(calendarWriter.upsert(eq(4), eq(21)))
        .thenThrow(new IllegalArgumentException("daysPerWeek must be 5 or 6: 4"));

    mockMvc
        .perform(
            put("/api/v1/work-calendar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daysPerWeek\":4,\"monthlyDivisor\":21}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void putWithAMissingFieldIs400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/work-calendar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daysPerWeek\":5}"))
        .andExpect(status().isBadRequest());
  }
}
