package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.domain.GroupAccessDeniedException;
import id.co.nativeapp.finance.consolidation.domain.GroupConsolidationRole;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.consolidation.service.GroupConsolidationReader;
import id.co.nativeapp.finance.consolidation.service.GroupConsolidationService;
import id.co.nativeapp.finance.group.service.GroupLeadResolver;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P3d #43 — the close path binds the RESOLVED lead, not the path/token candidate. {@link
 * GroupConsolidationService#closeGroup} validates the requester's company IS the group's lead (from
 * the authoritative {@code group_lead}), then passes THAT resolved lead INTO {@link
 * GroupCloseService#closeAs} — so the bound lead is structurally the validated one, never a value
 * re-resolved independently inside the close (which could, in principle, diverge).
 *
 * <p>Pure-unit (mocked collaborators) so the value threaded into the close is captured directly:
 * the lead {@code closeAs} receives must be the very {@link GroupLeadResolver} result, and a
 * requester whose company is NOT the resolved lead must be denied BEFORE any close is delegated.
 */
@ExtendWith(MockitoExtension.class)
class GroupConsolidationCloseLeadBindingTest {

  private static final String PERIOD = "2026-06";

  @Mock private GroupLeadResolver leadResolver;
  @Mock private GroupConsolidationReader reader;
  @Mock private GroupCloseService closeService;

  @Test
  void closeGroupPassesTheResolvedLeadIntoCloseAsNotTheRequesterCandidate() {
    GroupConsolidationService service =
        new GroupConsolidationService(leadResolver, reader, closeService);

    UUID groupId = UUID.randomUUID();
    UUID resolvedLead = UUID.randomUUID();
    // The requester's company EQUALS the resolved lead (so the lead gate passes), but it is a
    // DISTINCT UUID instance. The bound value must come from the resolver, threaded through
    // closeAs.
    UUID requesterCompany = UUID.fromString(resolvedLead.toString());
    GroupRequester requester =
        new GroupRequester(requesterCompany, "user", Set.of(GroupConsolidationRole.CLOSE));

    when(leadResolver.leadOf(groupId)).thenReturn(Optional.of(resolvedLead));
    when(closeService.closeAs(any(), eq(groupId), eq(PERIOD), eq(1)))
        .thenReturn(new GroupCloseResult(ConsolidationState.CLOSED, UUID.randomUUID(), 1, true));

    service.closeGroup(requester, groupId, PERIOD, 1);

    // closeAs received exactly the RESOLVED lead (the GroupLeadResolver result), not blindly the
    // requester candidate — and never the deprecated self-resolving close(groupId, ...) overload.
    ArgumentCaptor<UUID> boundLead = ArgumentCaptor.forClass(UUID.class);
    verify(closeService).closeAs(boundLead.capture(), eq(groupId), eq(PERIOD), eq(1));
    assertThat(boundLead.getValue()).isEqualTo(resolvedLead);
    verify(closeService, never()).close(any(), any(), anyInt());
  }

  @Test
  void aRequesterWhoIsNotTheResolvedLeadIsDeniedBeforeAnyCloseIsDelegated() {
    GroupConsolidationService service =
        new GroupConsolidationService(leadResolver, reader, closeService);

    UUID groupId = UUID.randomUUID();
    UUID resolvedLead = UUID.randomUUID();
    UUID notTheLead = UUID.randomUUID();
    GroupRequester requester =
        new GroupRequester(notTheLead, "user", Set.of(GroupConsolidationRole.CLOSE));

    when(leadResolver.leadOf(groupId)).thenReturn(Optional.of(resolvedLead));

    assertThatThrownBy(() -> service.closeGroup(requester, groupId, PERIOD, 1))
        .isInstanceOf(GroupAccessDeniedException.class);

    // No close (in either form) was delegated — the lead gate fails closed before binding.
    verify(closeService, never()).closeAs(any(), any(), any(), anyInt());
    verify(closeService, never()).close(any(), any(), anyInt());
  }
}
