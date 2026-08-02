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
import id.co.nativeapp.employee.timeoff.controller.OvertimeEntryController;
import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntryNotFoundException;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryReader;
import id.co.nativeapp.employee.timeoff.service.OvertimeEntryService;
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
 * Web-slice test for {@link OvertimeEntryController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, and happy paths. Services mocked.
 */
@WebMvcTest(OvertimeEntryController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class OvertimeEntryControllerTest {

  private static final UUID ENTRY = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OvertimeEntryService entryService;
  @MockitoBean private OvertimeEntryReader entryReader;

  private static OvertimeEntry submittedEntry() {
    return new OvertimeEntry(EMPLOYEE, LocalDate.of(2026, 8, 10), 120, DayKind.WEEKDAY, "k-0");
  }

  @Test
  void listReturns200WithThePaginationEnvelope() throws Exception {
    when(entryReader.forManager(any(), any(), any()))
        .thenReturn(PageResponse.of(List.of(), 0, 25, 0));

    mockMvc
        .perform(get("/api/v1/overtime-entries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
  }

  @Test
  void listWithAnUnknownStatusIs400() throws Exception {
    when(entryReader.forManager(eq("bogus"), any(), any()))
        .thenThrow(new IllegalArgumentException("Unknown overtime entry status: bogus"));

    mockMvc
        .perform(get("/api/v1/overtime-entries?status=bogus"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getOfAnUnknownEntryIs404() throws Exception {
    when(entryReader.one(ENTRY)).thenThrow(new OvertimeEntryNotFoundException(ENTRY));

    mockMvc
        .perform(get("/api/v1/overtime-entries/" + ENTRY))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/overtime-entry-not-found"));
  }

  @Test
  void approveWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/overtime-entries/" + ENTRY + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void approveReturns200() throws Exception {
    OvertimeEntry approved = submittedEntry();
    approved.approve("manager-sub", "ok", Instant.parse("2026-08-01T09:00:00Z"));
    when(entryService.approve(eq(ENTRY), isNull(), eq("k-1"))).thenReturn(approved);

    mockMvc
        .perform(
            post("/api/v1/overtime-entries/" + ENTRY + "/approve")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void rejectWithoutANoteIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/overtime-entries/" + ENTRY + "/reject")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectReturns200() throws Exception {
    OvertimeEntry rejected = submittedEntry();
    rejected.reject("manager-sub", "no", Instant.parse("2026-08-01T09:00:00Z"));
    when(entryService.reject(eq(ENTRY), eq("no"), eq("k-3"))).thenReturn(rejected);

    mockMvc
        .perform(
            post("/api/v1/overtime-entries/" + ENTRY + "/reject")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"no\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }
}
