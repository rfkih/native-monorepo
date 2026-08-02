package id.co.nativeapp.employee.expense;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.expense.controller.ExpenseClaimController;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.RefusalCommentRequiredException;
import id.co.nativeapp.employee.expense.domain.SelfApprovalException;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.service.ExpenseClaimReader;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link ExpenseClaimController}: validation 400s, missing {@code
 * Idempotency-Key} 400, 404 anti-enumeration, self-approval 403, and happy paths. Services mocked.
 */
@WebMvcTest(ExpenseClaimController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class ExpenseClaimControllerTest {

  private static final UUID CLAIM = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID EMPLOYEE = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseClaimService claimService;
  @MockitoBean private ExpenseClaimReader claimReader;

  private static ExpenseClaim submittedClaim() {
    ExpenseClaim claim =
        new ExpenseClaim(
            EMPLOYEE,
            CATEGORY,
            ORG_UNIT,
            Money.ofMinor(250_000L, "IDR"),
            LocalDate.of(2026, 7, 15),
            "Warung Makan",
            "lunch",
            null);
    claim.submit();
    return claim;
  }

  @Test
  void listReturns200() throws Exception {
    mockMvc.perform(get("/api/v1/expense-claims")).andExpect(status().isOk());
  }

  @Test
  void getOfAnUnknownClaimIs404() throws Exception {
    when(claimReader.one(CLAIM)).thenThrow(new ClaimNotFoundException(CLAIM));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/expense-claim-not-found"));
  }

  @Test
  void getReturns200() throws Exception {
    when(claimReader.one(CLAIM)).thenReturn(ExpenseClaimResponse.from(submittedClaim()));

    mockMvc
        .perform(get("/api/v1/expense-claims/" + CLAIM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.SUBMITTED.name()));
  }

  @Test
  void approveWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void approveReturns200() throws Exception {
    ExpenseClaim approved = submittedClaim();
    approved.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    when(claimService.approve(eq(CLAIM), isNull(), eq("k-1"))).thenReturn(approved);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.APPROVED.name()));
  }

  @Test
  void approveOwnClaimIs403() throws Exception {
    when(claimService.approve(any(), any(), any())).thenThrow(new SelfApprovalException(CLAIM));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/approve")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/self-approval-forbidden"));
  }

  @Test
  void refuseWithoutACommentIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refuseWithoutAnIdempotencyKeyIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no receipt\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/missing-required-header"));
  }

  @Test
  void refuseReturns200() throws Exception {
    ExpenseClaim refused = submittedClaim();
    refused.refuse("manager-sub", "no receipt", Instant.parse("2026-07-20T09:00:00Z"));
    when(claimService.refuse(eq(CLAIM), eq("no receipt"), eq("k-4"))).thenReturn(refused);

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .header("Idempotency-Key", "k-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"no receipt\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(ClaimStatus.REFUSED.name()));
  }

  @Test
  void refuseWithABlankCommentSurfacesTheDomainRuleAs422() throws Exception {
    when(claimService.refuse(eq(CLAIM), any(), eq("k-5")))
        .thenThrow(new RefusalCommentRequiredException(CLAIM));

    mockMvc
        .perform(
            post("/api/v1/expense-claims/" + CLAIM + "/refuse")
                .header("Idempotency-Key", "k-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"x\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/refusal-comment-required"));
  }
}
