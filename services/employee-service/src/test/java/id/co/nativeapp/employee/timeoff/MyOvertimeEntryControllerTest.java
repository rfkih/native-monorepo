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
import id.co.nativeapp.employee.timeoff.controller.MyOvertimeEntryController;
import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntryNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStateException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryReader;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
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
 * Web-slice test for {@link MyOvertimeEntryController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, and happy paths. Services mocked.
 */
@WebMvcTest(MyOvertimeEntryController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class MyOvertimeEntryControllerTest {

  private static final UUID ENTRY = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  private static final String VALID_BODY =
      "{\"workDate\":\"2026-08-10\",\"minutes\":120,\"dayKind\":\"WEEKDAY\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OvertimeEntryService entryService;
  @MockitoBean private OvertimeEntryReader entryReader;

  private static OvertimeEntry sampleEntry() {
    return new OvertimeEntry(EMPLOYEE, LocalDate.of(2026, 8, 10), 120, DayKind.WEEKDAY, "k-0");
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    when(entryService.create(any(), eq(120), any(), eq("k-1"))).thenReturn(sampleEntry());

    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value(TimeoffStatus.SUBMITTED.name()))
        .andExpect(jsonPath("$.minutes").value(120));
  }

  @Test
  void createWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void createWithMinutesAboveTheCapIs400() throws Exception {
    String body = "{\"workDate\":\"2026-08-10\",\"minutes\":601,\"dayKind\":\"WEEKDAY\"}";
    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithAnUnknownDayKindIs400() throws Exception {
    String body = "{\"workDate\":\"2026-08-10\",\"minutes\":120,\"dayKind\":\"BOGUS\"}";
    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(post("/api/v1/me/overtime-entries/" + ENTRY + "/cancel"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void cancelReturns200() throws Exception {
    OvertimeEntry cancelled = sampleEntry();
    cancelled.cancel();
    when(entryService.cancel(eq(ENTRY), eq("k-2"))).thenReturn(cancelled);

    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries/" + ENTRY + "/cancel")
                .header("Idempotency-Key", "k-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(TimeoffStatus.CANCELLED.name()));
  }

  @Test
  void cancelANonSubmittedEntryIs409() throws Exception {
    when(entryService.cancel(eq(ENTRY), eq("k-3")))
        .thenThrow(new TimeoffStateException("overtime entry", TimeoffStatus.APPROVED, "cancel"));

    mockMvc
        .perform(
            post("/api/v1/me/overtime-entries/" + ENTRY + "/cancel")
                .header("Idempotency-Key", "k-3"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/timeoff-state-conflict"));
  }

  @Test
  void getOfAnUnknownOrNotOwnedEntryIs404() throws Exception {
    when(entryReader.myEntry(ENTRY)).thenThrow(new OvertimeEntryNotFoundException(ENTRY));

    mockMvc
        .perform(get("/api/v1/me/overtime-entries/" + ENTRY))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/overtime-entry-not-found"));
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(entryReader.myEntries(any(), any())).thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/me/overtime-entries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }
}
