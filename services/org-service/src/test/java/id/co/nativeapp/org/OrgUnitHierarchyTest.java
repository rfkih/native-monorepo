package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The org-tree hierarchy invariant, proven on the {@link OrgUnit} aggregate + {@link OrgUnitType}
 * with no Spring context — the cheapest place to pin the {@code business_unit > branch > outlet >
 * team} nesting and the rename/move/deactivate behaviour.
 */
class OrgUnitHierarchyTest {

  private static final UUID LE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

  private static OrgUnit node(OrgUnitType type, UUID parentId, OrgUnitType parentType) {
    return new OrgUnit("node", type, parentId, parentType, LE, TODAY);
  }

  @Test
  void theNestingChainBusinessUnitBranchOutletTeamIsAccepted() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);
    OrgUnit branch = node(OrgUnitType.BRANCH, bu.getId(), OrgUnitType.BUSINESS_UNIT);
    OrgUnit outlet = node(OrgUnitType.OUTLET, branch.getId(), OrgUnitType.BRANCH);
    OrgUnit team = node(OrgUnitType.TEAM, outlet.getId(), OrgUnitType.OUTLET);

    assertThat(bu.getParentId()).isNull();
    assertThat(branch.getParentId()).isEqualTo(bu.getId());
    assertThat(outlet.getParentId()).isEqualTo(branch.getId());
    assertThat(team.getParentId()).isEqualTo(outlet.getId());
    // A fresh node is active and open-ended (the 9999-12-31 sentinel, never null).
    assertThat(team.isActive()).isTrue();
    assertThat(team.getEffectiveTo()).isEqualTo(OrgUnit.OPEN_ENDED);
  }

  @Test
  void aTeamDirectlyUnderABusinessUnitIsRejected() {
    UUID buId = UUID.randomUUID();
    assertThatThrownBy(() -> node(OrgUnitType.TEAM, buId, OrgUnitType.BUSINESS_UNIT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OUTLET");
  }

  @Test
  void aNonRootTypeAtTheTopLevelIsRejected() {
    assertThatThrownBy(() -> node(OrgUnitType.BRANCH, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBusinessUnitWithAParentIsRejected() {
    UUID someParent = UUID.randomUUID();
    assertThatThrownBy(() -> node(OrgUnitType.BUSINESS_UNIT, someParent, OrgUnitType.BUSINESS_UNIT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renameReportsWhetherItChanged() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);
    assertThat(bu.rename("New Name")).isTrue();
    assertThat(bu.getName()).isEqualTo("New Name");
    assertThat(bu.rename("New Name")).isFalse();
    assertThatThrownBy(() -> bu.rename("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void moveRevalidatesTheTypeRuleAndRejectsSelfParent() {
    OrgUnit bu1 = node(OrgUnitType.BUSINESS_UNIT, null, null);
    OrgUnit bu2 = node(OrgUnitType.BUSINESS_UNIT, null, null);
    OrgUnit branch = node(OrgUnitType.BRANCH, bu1.getId(), OrgUnitType.BUSINESS_UNIT);

    // Move the branch under another business unit: legal, and reports a change.
    assertThat(branch.moveTo(bu2.getId(), OrgUnitType.BUSINESS_UNIT)).isTrue();
    assertThat(branch.getParentId()).isEqualTo(bu2.getId());

    // Moving a branch under an outlet is illegal (a branch must sit under a business_unit).
    assertThatThrownBy(() -> branch.moveTo(UUID.randomUUID(), OrgUnitType.OUTLET))
        .isInstanceOf(IllegalArgumentException.class);

    // A node cannot become its own parent.
    assertThatThrownBy(() -> branch.moveTo(branch.getId(), OrgUnitType.BUSINESS_UNIT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deactivateClosesTheEffectivePeriodOnceAndIsIdempotent() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);
    LocalDate closeDate = LocalDate.of(2026, 12, 31);

    assertThat(bu.deactivate(closeDate)).isTrue();
    assertThat(bu.isActive()).isFalse();
    assertThat(bu.getEffectiveTo()).isEqualTo(closeDate);

    // A second deactivation is a no-op.
    assertThat(bu.deactivate(LocalDate.of(2027, 1, 1))).isFalse();
    assertThat(bu.getEffectiveTo()).isEqualTo(closeDate);
  }

  @Test
  void orgUnitTypeEncodesTheSingleLegalParent() {
    assertThat(OrgUnitType.BUSINESS_UNIT.isRoot()).isTrue();
    assertThat(OrgUnitType.BRANCH.requiredParentType()).contains(OrgUnitType.BUSINESS_UNIT);
    assertThat(OrgUnitType.OUTLET.requiredParentType()).contains(OrgUnitType.BRANCH);
    assertThat(OrgUnitType.TEAM.requiredParentType()).contains(OrgUnitType.OUTLET);
    assertThat(OrgUnitType.OUTLET.canBeChildOf(OrgUnitType.BRANCH)).isTrue();
    assertThat(OrgUnitType.OUTLET.canBeChildOf(OrgUnitType.BUSINESS_UNIT)).isFalse();
    assertThat(OrgUnitType.from("business_unit")).isEqualTo(OrgUnitType.BUSINESS_UNIT);
    assertThatThrownBy(() -> OrgUnitType.from("squad"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
