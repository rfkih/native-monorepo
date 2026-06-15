package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.domain.GroupAccessDeniedException;
import id.co.nativeapp.finance.consolidation.domain.GroupConsolidationRole;
import id.co.nativeapp.finance.consolidation.domain.GroupRoleDeniedException;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupConsolidationResponse;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.finance.consolidation.service.GroupConsolidationService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * P3d SEAM 4b — the group AUTHORIZATION + READ surface, against the REAL Flyway migration + the
 * two-GUC conjunction RLS under the unprivileged {@code app_user} (FORCE RLS binds even the owning
 * role). Drives {@link GroupConsolidationService} directly (the controller is a thin shell over it;
 * the HTTP contract is proven separately in {@code GroupConsolidationControllerTest}), so the
 * security crux runs against the genuine RLS:
 *
 * <ul>
 *   <li>the LEAD with the role reads the group consolidation summary (the eliminated total + flags)
 *       — and the figure is the GROUP total, never a member's standalone revenue;
 *   <li>a NON-lead member company is DENIED (404, reads nothing) — it cannot bind a group it does
 *       not lead, even with the role;
 *   <li>a request WITHOUT the group role is DENIED (403), before any scope is bound;
 *   <li>a request for a group the company does NOT lead is DENIED (cannot bind an arbitrary group);
 *   <li>the close endpoint triggers a close and returns the right state (a CLOSED close, and a held
 *       PENDING_MEMBERS);
 *   <li>default-deny: an unknown group reads nothing / is denied.
 * </ul>
 */
@SpringBootTest
class GroupConsolidationAuthorizationTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupConsolidationService consolidationService;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUpFixtures() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  private GroupRequester requester(UUID companyId, String... roles) {
    return new GroupRequester(companyId, "user-" + companyId, Set.of(roles));
  }

  // ----------------------------------------------------------------------- read happy path

  @Test
  void theLeadWithTheRoleReadsTheGroupTotalAndFlagsNeverAMemberStandaloneFigure() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));
    // A: revenue 10M, expense 4M. B: revenue 7M, expense 2M. No intercompany.
    fx.ingestTrialBalance(
        groupId,
        memberA,
        PERIOD,
        "IDR",
        List.of(revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));
    fx.ingestTrialBalance(
        groupId,
        memberB,
        PERIOD,
        "IDR",
        List.of(revenue(7_000_000L, "IDR"), expense(2_000_000L, "IDR")));

    // The lead-admin closes, then reads.
    GroupCloseResponse close =
        consolidationService.closeGroup(
            requester(lead, GroupConsolidationRole.CLOSE), groupId, PERIOD, 1);
    assertThat(close.closed()).isTrue();
    assertThat(close.state()).isEqualTo(ConsolidationState.CLOSED);

    Optional<GroupConsolidationResponse> read =
        consolidationService.readConsolidation(
            requester(lead, GroupConsolidationRole.VIEW), groupId, PERIOD);

    assertThat(read).isPresent();
    GroupConsolidationResponse dto = read.get();
    // The GROUP total (17M revenue, 6M expense, 11M net) — the consolidated sum, NOT a member's 10M
    // or 7M standalone revenue.
    assertThat(dto.groupRevenueMinor()).isEqualTo(17_000_000L);
    assertThat(dto.groupExpenseMinor()).isEqualTo(6_000_000L);
    assertThat(dto.groupNetMinor()).isEqualTo(11_000_000L);
    assertThat(dto.reportingCurrency()).isEqualTo("IDR");
    assertThat(dto.state()).isEqualTo(ConsolidationState.CLOSED);
    // A same-currency close translates nothing and uses no stub FX: the badges are honest.
    assertThat(dto.usesSimplifiedTranslationPolicy()).isFalse();
    assertThat(dto.usesStubFx()).isFalse();
    // No member's standalone figure equals the group total — and there is no member figure on the
    // DTO at all (the record carries only group-level fields).
    assertThat(dto.groupRevenueMinor()).isNotEqualTo(10_000_000L).isNotEqualTo(7_000_000L);
  }

  // ----------------------------------------------------------------------- denials

  @Test
  void aNonLeadMemberCompanyIsDeniedAndReadsNothingEvenWithTheRole() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, member, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, member, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));
    consolidationService.closeGroup(
        requester(lead, GroupConsolidationRole.CLOSE), groupId, PERIOD, 1);

    // The MEMBER company holds the read role but is NOT the lead -> denied 404, binds no group
    // scope.
    assertThatThrownBy(
            () ->
                consolidationService.readConsolidation(
                    requester(member, GroupConsolidationRole.VIEW), groupId, PERIOD))
        .isInstanceOf(GroupAccessDeniedException.class);
    // And it cannot trigger a close either.
    assertThatThrownBy(
            () ->
                consolidationService.closeGroup(
                    requester(member, GroupConsolidationRole.CLOSE), groupId, PERIOD, 2))
        .isInstanceOf(GroupAccessDeniedException.class);
  }

  @Test
  void aRequestWithoutTheGroupRoleIsDeniedBeforeAnyScopeIsBound() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, member, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, member, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    // The LEAD, but with NO group role -> 403 (role gate fails before the lead binding).
    assertThatThrownBy(
            () -> consolidationService.readConsolidation(requester(lead), groupId, PERIOD))
        .isInstanceOf(GroupRoleDeniedException.class);
    // The read role does NOT authorize a close (close needs the strictly higher CLOSE role) -> 403.
    assertThatThrownBy(
            () ->
                consolidationService.closeGroup(
                    requester(lead, GroupConsolidationRole.VIEW), groupId, PERIOD, 1))
        .isInstanceOf(GroupRoleDeniedException.class);
  }

  @Test
  void aRequestForAGroupTheCompanyDoesNotLeadCannotBindAnArbitraryGroup() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID otherCompany = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, lead, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, lead, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    // Another company, holding BOTH roles, points at a real group it does not lead -> 404 (cannot
    // bind an arbitrary group from the token; the existence is not even disclosed).
    assertThatThrownBy(
            () ->
                consolidationService.readConsolidation(
                    requester(otherCompany, GroupConsolidationRole.VIEW), groupId, PERIOD))
        .isInstanceOf(GroupAccessDeniedException.class);
    assertThatThrownBy(
            () ->
                consolidationService.closeGroup(
                    requester(otherCompany, GroupConsolidationRole.CLOSE), groupId, PERIOD, 1))
        .isInstanceOf(GroupAccessDeniedException.class);
  }

  @Test
  void anUnknownGroupIsDeniedDefaultDeny() {
    // No GroupDefined consumed for this group -> the lead is unknown -> 404 (same as not-the-lead,
    // no
    // existence disclosure). The group scope is never bound; default-deny.
    UUID unknownGroup = UUID.randomUUID();
    UUID anyCompany = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                consolidationService.readConsolidation(
                    requester(anyCompany, GroupConsolidationRole.VIEW), unknownGroup, PERIOD))
        .isInstanceOf(GroupAccessDeniedException.class);
  }

  @Test
  void aReadBeforeAnyCloseReturnsEmptyForTheLead() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    fx.defineGroup(groupId, lead, "IDR");

    // The lead is entitled, but no close has run yet -> empty (the controller renders 204), NOT a
    // denial and NOT a fabricated zero.
    Optional<GroupConsolidationResponse> read =
        consolidationService.readConsolidation(
            requester(lead, GroupConsolidationRole.VIEW), groupId, PERIOD);
    assertThat(read).isEmpty();
  }

  // ----------------------------------------------------------------------- close states

  @Test
  void theCloseEndpointTriggersACloseAndReturnsTheState() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, member, LocalDate.of(2026, 1, 1));
    fx.ingestTrialBalance(groupId, member, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    GroupCloseResponse response =
        consolidationService.closeGroup(
            requester(lead, GroupConsolidationRole.CLOSE), groupId, PERIOD, 1);

    assertThat(response.state()).isEqualTo(ConsolidationState.CLOSED);
    assertThat(response.closed()).isTrue();
    assertThat(response.firstRun()).isTrue();
    assertThat(response.closeRunSeq()).isEqualTo(1);
    assertThat(response.groupId()).isEqualTo(groupId);
  }

  @Test
  void theCloseEndpointReturnsAHeldPendingMembersState() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberA = UUID.randomUUID();
    UUID memberB = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberA, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberB, LocalDate.of(2026, 1, 1));
    // Only A reports; B (active at period-end) has no trial balance -> the close is HELD.
    fx.ingestTrialBalance(groupId, memberA, PERIOD, "IDR", List.of(revenue(10_000_000L, "IDR")));

    GroupCloseResponse response =
        consolidationService.closeGroup(
            requester(lead, GroupConsolidationRole.CLOSE), groupId, PERIOD, 1);

    assertThat(response.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS);
    assertThat(response.closed()).isFalse();

    // Reading the held close surfaces the GROUP-level held state + a zero group total (never a
    // member's standalone figure).
    GroupConsolidationResponse read =
        consolidationService
            .readConsolidation(requester(lead, GroupConsolidationRole.VIEW), groupId, PERIOD)
            .orElseThrow();
    assertThat(read.state()).isEqualTo(ConsolidationState.PENDING_MEMBERS);
    assertThat(read.groupRevenueMinor()).isZero();
    // A's 10M is NEVER exposed through the held group read.
    assertThat(read.groupRevenueMinor()).isNotEqualTo(10_000_000L);
  }
}
