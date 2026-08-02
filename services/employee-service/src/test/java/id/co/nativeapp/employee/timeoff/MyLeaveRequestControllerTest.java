package id.co.nativeapp.employee.timeoff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.timeoff.controller.MyLeaveRequestController;
import id.co.nativeapp.employee.timeoff.domain.LeaveOverlapException;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequestNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStateException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestReader;
import id.co.nativeapp.employee.timeoff.service.LeaveRequestService;
import id.co.nativeapp.security.ApiExceptionHandler;
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
 * Web-slice test for {@link MyLeaveRequestController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, and happy paths. Services mocked.
 */
@WebMvcTest(MyLeaveRequestController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class MyLeaveRequestControllerTest {

  private static final UUID REQUEST = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  private static final String VALID_BODY =
      "{\"leaveType\":\"ANNUAL\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-12\",\"days\":3}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LeaveRequestService requestService;
  @MockitoBean private LeaveRequestReader requestReader;

  private static LeaveRequest sampleRequest() {
    return new LeaveRequest(
        EMPLOYEE, LeaveType.ANNUAL, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12), 3, "k-0");
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    when(requestService.create(any(), any(), any(), eq(3), eq("k-1"))).thenReturn(sampleRequest());

    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value(TimeoffStatus.SUBMITTED.name()))
        .andExpect(jsonPath("$.days").value(3));
  }

  @Test
  void createWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void createWithAMissingLeaveTypeIs400() throws Exception {
    String body = "{\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-12\",\"days\":3}";
    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithAnUnknownLeaveTypeIs400() throws Exception {
    String body =
        "{\"leaveType\":\"BOGUS\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-12\",\"days\":3}";
    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithANonPositiveDaysIs400() throws Exception {
    String body =
        "{\"leaveType\":\"ANNUAL\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-12\",\"days\":0}";
    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createOverlappingAnExistingRequestIs409() throws Exception {
    when(requestService.create(any(), any(), any(), eq(3), eq("k-2")))
        .thenThrow(new LeaveOverlapException(EMPLOYEE));

    mockMvc
        .perform(
            post("/api/v1/me/leave-requests")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/leave-request-overlap"));
  }

  @Test
  void cancelWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(post("/api/v1/me/leave-requests/" + REQUEST + "/cancel"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void cancelReturns200() throws Exception {
    LeaveRequest cancelled = sampleRequest();
    cancelled.cancel();
    when(requestService.cancel(eq(REQUEST), eq("k-3"))).thenReturn(cancelled);

    mockMvc
        .perform(
            post("/api/v1/me/leave-requests/" + REQUEST + "/cancel")
                .header("Idempotency-Key", "k-3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(TimeoffStatus.CANCELLED.name()));
  }

  @Test
  void cancelANonSubmittedRequestIs409() throws Exception {
    when(requestService.cancel(eq(REQUEST), eq("k-4")))
        .thenThrow(new TimeoffStateException("leave request", TimeoffStatus.APPROVED, "cancel"));

    mockMvc
        .perform(
            post("/api/v1/me/leave-requests/" + REQUEST + "/cancel")
                .header("Idempotency-Key", "k-4"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/timeoff-state-conflict"));
  }

  @Test
  void getOfAnUnknownOrNotOwnedRequestIs404() throws Exception {
    when(requestReader.myRequest(REQUEST)).thenThrow(new LeaveRequestNotFoundException(REQUEST));

    mockMvc
        .perform(get("/api/v1/me/leave-requests/" + REQUEST))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/leave-request-not-found"));
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(requestReader.myRequests(any(), any())).thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/me/leave-requests"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
  }
}
