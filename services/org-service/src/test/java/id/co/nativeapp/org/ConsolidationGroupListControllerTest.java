package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.group.controller.GroupController;
import id.co.nativeapp.org.group.dto.ConsolidationGroupListResponse;
import id.co.nativeapp.org.group.dto.GroupMembershipListResponse;
import id.co.nativeapp.org.group.service.GroupService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for the two new GET endpoints on {@link GroupController}:
 *
 * <ul>
 *   <li>{@code GET /api/v1/consolidation-groups} — 200 with groups list; 200 with empty list.
 *   <li>{@code GET /api/v1/consolidation-groups/{groupId}/members} — 200 with members list; 404
 *       when group not found / not led by the caller (empty from service).
 * </ul>
 *
 * <p>No DB. The service now returns DTOs directly (projection-to-DTO mapping happens in the service
 * layer — CODE-STRUCTURE §3.3), so the mock returns DTO instances.
 */
@WebMvcTest(GroupController.class)
@Import(ApiExceptionHandler.class)
class ConsolidationGroupListControllerTest {

  private static final UUID GROUP_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID LEAD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID MEMBER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GroupService groupService;

  // --- GET /api/v1/consolidation-groups ----------------------------------------

  @Test
  void listGroupsReturns200WithGroupList() throws Exception {
    ConsolidationGroupListResponse stub =
        new ConsolidationGroupListResponse(GROUP_ID, "Acme Group", LEAD_ID, "IDR");

    when(groupService.findAllGroupsForCurrentTenant()).thenReturn(List.of(stub));

    mockMvc
        .perform(get("/api/v1/consolidation-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(GROUP_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("Acme Group"))
        .andExpect(jsonPath("$[0].leadCompanyId").value(LEAD_ID.toString()))
        .andExpect(jsonPath("$[0].reportingCurrency").value("IDR"));
  }

  @Test
  void listGroupsReturns200WithEmptyListWhenNoGroupsExist() throws Exception {
    when(groupService.findAllGroupsForCurrentTenant()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/consolidation-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  // --- GET /api/v1/consolidation-groups/{groupId}/members ----------------------

  @Test
  void listMembersReturns200WithMemberList() throws Exception {
    GroupMembershipListResponse stub =
        new GroupMembershipListResponse(
            MEMBER_ID, LocalDate.of(2026, 1, 1), LocalDate.of(9999, 12, 31), true);

    when(groupService.findMembersForGroup(GROUP_ID)).thenReturn(List.of(stub));

    mockMvc
        .perform(get("/api/v1/consolidation-groups/" + GROUP_ID + "/members"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].memberCompanyId").value(MEMBER_ID.toString()))
        .andExpect(jsonPath("$[0].effectiveFrom").value("2026-01-01"))
        .andExpect(jsonPath("$[0].active").value(true));
  }

  @Test
  void listMembersReturns404WhenGroupNotFoundOrNotLedByCaller() throws Exception {
    when(groupService.findMembersForGroup(GROUP_ID)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/consolidation-groups/" + GROUP_ID + "/members"))
        .andExpect(status().isNotFound());
  }
}
