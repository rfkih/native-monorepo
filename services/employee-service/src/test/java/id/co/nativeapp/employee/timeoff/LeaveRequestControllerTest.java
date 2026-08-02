package id.co.nativeapp.employee.timeoff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.timeoff.controller.LeaveRequestController;
import id.co.nativeapp.employee.timeoff.domain.InsufficientLeaveBalanceException;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequestNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestReader;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.time.LocalDate;
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
 * Web-slice test for {@link LeaveRequestController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, and happy paths. Services mocked.
 */
@WebMvcTest(LeaveRequestController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class LeaveRequestControllerTest {

  private static final UUID REQUEST = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LeaveRequestService requestService;
  @MockitoBean private LeaveRequestReader requestReader;

  private static LeaveRequest submittedRequest() {
    return new LeaveRequest(
        EMPLOYEE, LeaveType.ANNUAL, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), 3, "k-0");
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(requestReader.forManager(any(), any(), any()))
        .thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/leave-requests"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void listWithAnUnknownStatusIs400() throws Exception {
    when(requestReader.forManager(eq("bogus"), any(), any()))
        .thenThrow(new IllegalArgumentException("Unknown leave request status: bogus"));

    mockMvc.perform(get("/api/v1/leave-requests?status=bogus")).andExpect(status().isBadRequest());
  }

  @Test
  void getOfAnUnknownRequestIs404() throws Exception {
    when(requestReader.one(REQUEST)).thenThrow(new LeaveRequestNotFoundException(REQUEST));

    mockMvc
        .perform(get("/api/v1/leave-requests/" + REQUEST))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/leave-request-not-found"));
  }

  @Test
  void getReturns200() throws Exception {
    when(requestReader.one(REQUEST)).thenReturn(LeaveRequestResponse.from(submittedRequest()));

    mockMvc.perform(get("/api/v1/leave-requests/" + REQUEST)).andExpect(status().isOk());
  }

  @Test
  void approveWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/leave-requests/" + REQUEST + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void approveReturns200() throws Exception {
    LeaveRequest approved = submittedRequest();
    approved.approve("manager-sub", "ok", Instant.parse("2026-08-01T09:00:00Z"));
    when(requestService.approve(eq(REQUEST), isNull(), eq("k-1"))).thenReturn(approved);

    mockMvc
        .perform(
            post("/api/v1/leave-requests/" + REQUEST + "/approve")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void approveExceedingTheBalanceIs409() throws Exception {
    when(requestService.approve(eq(REQUEST), any(), eq("k-2")))
        .thenThrow(new InsufficientLeaveBalanceException(EMPLOYEE, 2026, 3, 1));

    mockMvc
        .perform(
            post("/api/v1/leave-requests/" + REQUEST + "/approve")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/insufficient-leave-balance"));
  }

  @Test
  void rejectWithoutANoteIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/leave-requests/" + REQUEST + "/reject")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectReturns200() throws Exception {
    LeaveRequest rejected = submittedRequest();
    rejected.reject("manager-sub", "no notice", Instant.parse("2026-08-01T09:00:00Z"));
    when(requestService.reject(eq(REQUEST), eq("no notice"), eq("k-4"))).thenReturn(rejected);

    mockMvc
        .perform(
            post("/api/v1/leave-requests/" + REQUEST + "/reject")
                .header("Idempotency-Key", "k-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"no notice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }
}
