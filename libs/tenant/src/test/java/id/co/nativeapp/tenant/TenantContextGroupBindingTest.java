package id.co.nativeapp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * FAST unit guards for the P3d SEAM 2 binding STATE MACHINE on {@link TenantContext} — proving the
 * group binding is purely additive and the single-tenant binding is unchanged.
 */
class TenantContextGroupBindingTest {

  @Test
  void plainBindingHasNoGroup() throws Exception {
    TenantContext.callAs(
        "company-A",
        "actor",
        () -> {
          assertThat(TenantContext.require().hasGroup()).isFalse();
          assertThat(TenantContext.require().groupIdOptional()).isEmpty();
          assertThat(TenantContext.currentGroupId()).isEmpty();
          assertThat(TenantContext.currentCompanyId()).contains("company-A");
          return null;
        });
  }

  @Test
  void groupBindingCarriesBothTenantAndGroup() throws Exception {
    TenantContext.callAsGroup(
        "lead-company",
        "group-G",
        "actor",
        () -> {
          assertThat(TenantContext.require().hasGroup()).isTrue();
          assertThat(TenantContext.require().companyId()).isEqualTo("lead-company");
          assertThat(TenantContext.require().groupId()).isEqualTo("group-G");
          assertThat(TenantContext.currentGroupId()).contains("group-G");
          assertThat(TenantContext.currentCompanyId()).contains("lead-company");
          return null;
        });
  }

  @Test
  void groupBindingDoesNotLeakAfterScope() throws Exception {
    TenantContext.callAsGroup("lead", "group-G", "actor", () -> null);
    // Outside any scope the group is gone (ScopedValue tears down).
    assertThat(TenantContext.currentGroupId()).isEmpty();
    assertThat(TenantContext.isBound()).isFalse();
  }

  @Test
  void nestedPlainBindingInsideAGroupScopeHasNoGroup() throws Exception {
    TenantContext.callAsGroup(
        "lead",
        "group-G",
        "actor",
        () ->
            TenantContext.callAs(
                "company-B",
                "actor-b",
                () -> {
                  // A plain re-binding shadows the group binding: the inner scope is single-tenant.
                  assertThat(TenantContext.require().hasGroup()).isFalse();
                  assertThat(TenantContext.currentGroupId()).isEmpty();
                  assertThat(TenantContext.currentCompanyId()).contains("company-B");
                  return null;
                }));
  }

  @Test
  void aBlankGroupIdIsRejected() {
    assertThatThrownBy(() -> new TenantContext.Tenant("c", "a", "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("groupId");
  }

  @Test
  void aNullGroupIdMeansNoGroup() {
    var tenant = new TenantContext.Tenant("c", "a", null);
    assertThat(tenant.hasGroup()).isFalse();
    assertThat(tenant.groupIdOptional()).isEmpty();
  }
}
