package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.consolidation.controller.GroupConsolidationAdvice;
import id.co.nativeapp.finance.consolidation.controller.GroupConsolidationController;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.domain.GroupAccessDeniedException;
import id.co.nativeapp.finance.consolidation.domain.GroupRoleDeniedException;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupConsolidationResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupEliminationStatusResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.finance.consolidation.service.GroupConsolidationService;
import id.co.nativeapp.finance.consolidation.service.GroupRequesterFactory;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the P3d SEAM 4b group surface — the HTTP contract of {@link
 * GroupConsolidationController}: a 200 with the group total + flags + eliminations on a read, a 204
 * when no close exists, a 200 with the state on a close, a 403 (RFC-7807) when the role is missing,
 * a 404 (RFC-7807, no existence disclosure) when the requester does not lead the group, and a 400
 * for a malformed period. The {@link GroupConsolidationService} + {@link GroupRequesterFactory} are
 * mocked — no DB, no security wiring needed (the authz decisions are the service's; here we prove
 * the controller maps them to the right status + body).
 */
@WebMvcTest(GroupConsolidationController.class)
@Import({GroupConsolidationAdvice.class, ConstraintViolationAdvice.class})
class GroupConsolidationControllerTest {

  private static final UUID GROUP = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID LEAD = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GroupConsolidationService consolidationService;
  @MockitoBean private GroupRequesterFactory requesterFactory;

  @BeforeEach
  void bindRequester() {
    when(requesterFactory.current())
        .thenReturn(new GroupRequester(LEAD, "user-lead", Set.of("group:consolidation:view")));
  }

  @Test
  void readReturnsTheGroupTotalFlagsAndEliminationsStatus() throws Exception {
    GroupConsolidationResponse dto =
        new GroupConsolidationResponse(
            GROUP,
            "2026-06",
            "IDR",
            17_000_000L,
            6_000_000L,
            11_000_000L,
            ConsolidationState.CLOSED,
            false,
            false,
            false,
            1,
            new GroupEliminationStatusResponse(1L, 0L, true));
    when(consolidationService.readConsolidation(any(), eq(GROUP), eq("2026-06")))
        .thenReturn(Optional.of(dto));

    mockMvc
        .perform(get("/api/v1/groups/{groupId}/consolidation", GROUP).param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groupRevenueMinor").value(17_000_000L))
        .andExpect(jsonPath("$.groupNetMinor").value(11_000_000L))
        .andExpect(jsonPath("$.reportingCurrency").value("IDR"))
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.eliminations.allReconciled").value(true))
        // The DTO carries NO member-scoped field at all (no member id / member revenue key).
        .andExpect(jsonPath("$.memberCompanyId").doesNotExist())
        .andExpect(jsonPath("$.members").doesNotExist());
  }

  @Test
  void readReturns204WhenNoCloseExistsYet() throws Exception {
    when(consolidationService.readConsolidation(any(), eq(GROUP), eq("2026-07")))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/groups/{groupId}/consolidation", GROUP).param("period", "2026-07"))
        .andExpect(status().isNoContent());
  }

  @Test
  void closeReturnsTheResultState() throws Exception {
    when(consolidationService.closeGroup(any(), eq(GROUP), eq("2026-06"), eq(2)))
        .thenReturn(
            new GroupCloseResponse(GROUP, "2026-06", ConsolidationState.CLOSED, 2, true, true));

    mockMvc
        .perform(
            post("/api/v1/groups/{groupId}/consolidation/close", GROUP)
                .param("period", "2026-06")
                .param("closeRunSeq", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.closed").value(true))
        .andExpect(jsonPath("$.closeRunSeq").value(2));
  }

  @Test
  void aMissingRoleMapsToAProblemDetail403() throws Exception {
    when(consolidationService.readConsolidation(any(), eq(GROUP), eq("2026-06")))
        .thenThrow(new GroupRoleDeniedException("no role"));

    mockMvc
        .perform(get("/api/v1/groups/{groupId}/consolidation", GROUP).param("period", "2026-06"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/group-consolidation-forbidden"));
  }

  @Test
  void aNonLeadMapsToAProblemDetail404WithoutDisclosingExistence() throws Exception {
    when(consolidationService.readConsolidation(any(), eq(GROUP), eq("2026-06")))
        .thenThrow(new GroupAccessDeniedException("not the lead"));

    mockMvc
        .perform(get("/api/v1/groups/{groupId}/consolidation", GROUP).param("period", "2026-06"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/group-not-found"))
        // The generic detail must not echo the group id (no existence disclosure).
        .andExpect(jsonPath("$.detail").value("No such group consolidation is accessible."));
  }

  @Test
  void aMalformedPeriodIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/groups/{groupId}/consolidation", GROUP).param("period", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
