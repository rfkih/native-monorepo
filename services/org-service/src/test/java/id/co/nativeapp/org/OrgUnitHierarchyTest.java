package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The org-tree invariant, proven on the {@link OrgUnit} aggregate + {@link OrgUnitType} with no
 * Spring context — the cheapest place to pin it.
 *
 * <p>Since ADR 0070 the invariant is FLATNESS, not nesting: the tree is {@code company > outlet},
 * {@code OUTLET} is the only kind, and every node is top-level. What this class used to assert (the
 * {@code business_unit > outlet > team} parent→child rules, the vertical-belongs-to-a-business-unit
 * rule, and the move/reparent behaviour) is gone with those levels — the vertical now lives on the
 * company, and there is nowhere to move an outlet to. The rename / deactivate / reactivate
 * behaviour is unchanged and still pinned here.
 */
class OrgUnitHierarchyTest {

  private static final UUID LE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate TODAY = LocalDate.of(2026, 6, 14);

  private static OrgUnit outlet() {
    return new OrgUnit("node", OrgUnitType.OUTLET, LE, TODAY);
  }

  // ---- flatness -------------------------------------------------------------------------------

  @Test
  void everyNewOrgUnitIsATopLevelOutlet() {
    OrgUnit unit = outlet();
    assertThat(unit.getType()).isEqualTo(OrgUnitType.OUTLET);
    assertThat(unit.getParentId()).isNull();
    assertThat(unit.isActive()).isTrue();
    assertThat(unit.getEffectiveFrom()).isEqualTo(TODAY);
    assertThat(unit.getEffectiveTo()).isEqualTo(OrgUnit.OPEN_ENDED);
    assertThat(unit.getLegalEmployerId()).isEqualTo(LE);
  }

  @Test
  void outletIsTheOnlyType() {
    assertThat(OrgUnitType.values()).containsExactly(OrgUnitType.OUTLET);
  }

  @Test
  void theRemovedLevelsNoLongerParse() {
    // An old client naming a retired level gets an explicit 400, not a silent substitution.
    assertThatThrownBy(() -> OrgUnitType.from("business_unit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("business_unit");
    assertThatThrownBy(() -> OrgUnitType.from("team"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("team");
  }

  @Test
  void theTypeParserAcceptsAnyCasingOfOutletAndRejectsBlanks() {
    assertThat(OrgUnitType.from("outlet")).isEqualTo(OrgUnitType.OUTLET);
    assertThat(OrgUnitType.from("  OuTlEt  ")).isEqualTo(OrgUnitType.OUTLET);
    assertThatThrownBy(() -> OrgUnitType.from(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgUnitType.from("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankNameIsRejected() {
    assertThatThrownBy(() -> new OrgUnit("  ", OrgUnitType.OUTLET, LE, TODAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  // ---- detachFromParent (the ADR 0070 migration path) -----------------------------------------

  @Test
  void detachFromParentIsANoopOnAnAlreadyTopLevelNode() {
    OrgUnit unit = outlet();
    assertThat(unit.detachFromParent()).isFalse();
    assertThat(unit.getParentId()).isNull();
  }

  // ---- rename ---------------------------------------------------------------------------------

  @Test
  void renameReportsWhetherTheNameActuallyChanged() {
    OrgUnit unit = outlet();
    assertThat(unit.rename("Kemang")).isTrue();
    assertThat(unit.getName()).isEqualTo("Kemang");
    // Same name (and the trimmed form of it) is not a change — the caller emits no event.
    assertThat(unit.rename("Kemang")).isFalse();
    assertThat(unit.rename("  Kemang  ")).isFalse();
    assertThatThrownBy(() -> unit.rename(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  // ---- deactivate / reactivate ----------------------------------------------------------------

  @Test
  void deactivateClosesTheEffectivePeriodAndIsIdempotent() {
    OrgUnit unit = outlet();
    LocalDate asOf = LocalDate.of(2026, 9, 1);

    assertThat(unit.deactivate(asOf)).isTrue();
    assertThat(unit.isActive()).isFalse();
    assertThat(unit.getEffectiveTo()).isEqualTo(asOf);

    // Already inactive — no state change, so the caller emits no second event.
    assertThat(unit.deactivate(asOf.plusDays(1))).isFalse();
    assertThat(unit.getEffectiveTo()).isEqualTo(asOf);
  }

  @Test
  void reactivateIsTheExactInverseOfDeactivate() {
    OrgUnit unit = outlet();
    unit.deactivate(LocalDate.of(2026, 9, 1));

    assertThat(unit.reactivate()).isTrue();
    assertThat(unit.isActive()).isTrue();
    assertThat(unit.getEffectiveTo()).isEqualTo(OrgUnit.OPEN_ENDED);

    // Already active — no state change.
    assertThat(unit.reactivate()).isFalse();
  }
}
