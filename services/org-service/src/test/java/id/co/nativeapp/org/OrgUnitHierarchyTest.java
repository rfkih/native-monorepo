package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.domain.Vertical;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The org-tree hierarchy invariant, proven on the {@link OrgUnit} aggregate + {@link OrgUnitType}
 * with no Spring context — the cheapest place to pin the {@code business_unit > outlet > team}
 * nesting (ADR 0012: the tree is flat — no branch level) and the rename/move/deactivate behaviour.
 */
class OrgUnitHierarchyTest {

  private static final UUID LE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

  private static OrgUnit node(OrgUnitType type, UUID parentId, OrgUnitType parentType) {
    // A BUSINESS_UNIT requires a vertical; every other type must carry none.
    Vertical vertical = type == OrgUnitType.BUSINESS_UNIT ? Vertical.RESTAURANT : null;
    return new OrgUnit("node", type, vertical, parentId, parentType, LE, TODAY);
  }

  @Test
  void aBusinessUnitWithoutAVerticalIsRejected() {
    assertThatThrownBy(
            () -> new OrgUnit("HQ", OrgUnitType.BUSINESS_UNIT, null, null, null, LE, TODAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vertical");
  }

  @Test
  void aNonBusinessUnitWithAVerticalIsRejected() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);
    assertThatThrownBy(
            () ->
                new OrgUnit(
                    "Outlet",
                    OrgUnitType.OUTLET,
                    Vertical.CARWASH,
                    bu.getId(),
                    OrgUnitType.BUSINESS_UNIT,
                    LE,
                    TODAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vertical");
  }

  @Test
  void verticalKeysAreLowercaseAndTheParserNormalizes() {
    assertThat(Vertical.RESTAURANT.key()).isEqualTo("restaurant");
    assertThat(Vertical.fromKey(" Carwash ")).isEqualTo(Vertical.CARWASH);
    assertThatThrownBy(() -> Vertical.fromKey("laundromat"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Vertical.fromKey("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theNestingChainBusinessUnitOutletTeamIsAccepted() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);
    OrgUnit outlet = node(OrgUnitType.OUTLET, bu.getId(), OrgUnitType.BUSINESS_UNIT);
    OrgUnit team = node(OrgUnitType.TEAM, outlet.getId(), OrgUnitType.OUTLET);

    assertThat(bu.getParentId()).isNull();
    assertThat(outlet.getParentId()).isEqualTo(bu.getId());
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
  void anOutletUnderAnOutletIsRejected() {
    UUID outletId = UUID.randomUUID();
    assertThatThrownBy(() -> node(OrgUnitType.OUTLET, outletId, OrgUnitType.OUTLET))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNonRootTypeAtTheTopLevelIsRejected() {
    assertThatThrownBy(() -> node(OrgUnitType.OUTLET, null, null))
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
    OrgUnit outlet = node(OrgUnitType.OUTLET, bu1.getId(), OrgUnitType.BUSINESS_UNIT);

    // Move the outlet under another business unit: legal, and reports a change.
    assertThat(outlet.moveTo(bu2.getId(), OrgUnitType.BUSINESS_UNIT)).isTrue();
    assertThat(outlet.getParentId()).isEqualTo(bu2.getId());

    // Moving an outlet under another outlet is illegal (an outlet sits under a business_unit).
    assertThatThrownBy(() -> outlet.moveTo(UUID.randomUUID(), OrgUnitType.OUTLET))
        .isInstanceOf(IllegalArgumentException.class);

    // A team may move between outlets.
    OrgUnit team = node(OrgUnitType.TEAM, outlet.getId(), OrgUnitType.OUTLET);
    OrgUnit otherOutlet = node(OrgUnitType.OUTLET, bu1.getId(), OrgUnitType.BUSINESS_UNIT);
    assertThat(team.moveTo(otherOutlet.getId(), OrgUnitType.OUTLET)).isTrue();
    assertThat(team.getParentId()).isEqualTo(otherOutlet.getId());

    // A node cannot become its own parent.
    assertThatThrownBy(() -> outlet.moveTo(outlet.getId(), OrgUnitType.BUSINESS_UNIT))
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
  void reactivateIsTheExactInverseOfDeactivateAndIsIdempotent() {
    OrgUnit bu = node(OrgUnitType.BUSINESS_UNIT, null, null);

    // Reactivating an already-active node is a no-op.
    assertThat(bu.reactivate()).isFalse();

    bu.deactivate(LocalDate.of(2026, 12, 31));
    assertThat(bu.isActive()).isFalse();

    // Reactivate reopens the effective period: active again, effective_to back to the sentinel.
    assertThat(bu.reactivate()).isTrue();
    assertThat(bu.isActive()).isTrue();
    assertThat(bu.getEffectiveTo()).isEqualTo(OrgUnit.OPEN_ENDED);

    // A second reactivation is a no-op.
    assertThat(bu.reactivate()).isFalse();
  }

  @Test
  void orgUnitTypeEncodesTheLegalParents() {
    assertThat(OrgUnitType.BUSINESS_UNIT.isRoot()).isTrue();
    assertThat(OrgUnitType.OUTLET.allowedParentTypes()).containsExactly(OrgUnitType.BUSINESS_UNIT);
    assertThat(OrgUnitType.TEAM.allowedParentTypes()).containsExactly(OrgUnitType.OUTLET);
    assertThat(OrgUnitType.OUTLET.canBeChildOf(OrgUnitType.BUSINESS_UNIT)).isTrue();
    assertThat(OrgUnitType.OUTLET.canBeChildOf(OrgUnitType.OUTLET)).isFalse();
    assertThat(OrgUnitType.TEAM.canBeChildOf(OrgUnitType.BUSINESS_UNIT)).isFalse();
    assertThat(OrgUnitType.from("business_unit")).isEqualTo(OrgUnitType.BUSINESS_UNIT);
    assertThatThrownBy(() -> OrgUnitType.from("branch"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgUnitType.from("squad"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
