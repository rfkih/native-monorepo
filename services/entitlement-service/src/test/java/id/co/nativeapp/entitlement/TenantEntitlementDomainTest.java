package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.domain.EntitlementStatus;
import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit domain tests for the {@link TenantEntitlement} aggregate's grant/revoke invariants —
 * no Spring, no DB (boot the least context that proves the assertion, ENGINEERING-STANDARDS §3.1).
 */
class TenantEntitlementDomainTest {

  private static final Instant T0 = Instant.parse("2026-06-14T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-06-15T00:00:00Z");
  private static final Instant T2 = Instant.parse("2026-06-16T00:00:00Z");

  @Test
  void aNewEntitlementStartsEntitledWithGrantedAtStampedAndNoRevokedAt() {
    TenantEntitlement entitlement = new TenantEntitlement("restaurant", T0);
    assertThat(entitlement.getModuleKey()).isEqualTo("restaurant");
    assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.ENTITLED);
    assertThat(entitlement.isEntitled()).isTrue();
    assertThat(entitlement.getGrantedAt()).isEqualTo(T0);
    assertThat(entitlement.getRevokedAt()).isNull();
  }

  @Test
  void revokeFlipsToRevokedAndStampsRevokedAt() {
    TenantEntitlement entitlement = new TenantEntitlement("finance", T0);
    entitlement.revoke(T1);
    assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.REVOKED);
    assertThat(entitlement.isEntitled()).isFalse();
    assertThat(entitlement.getRevokedAt()).isEqualTo(T1);
  }

  @Test
  void regrantAfterRevokeReEntitlesAndClearsRevokedAt() {
    TenantEntitlement entitlement = new TenantEntitlement("hr", T0);
    entitlement.revoke(T1);
    entitlement.grant(T2);
    assertThat(entitlement.getStatus()).isEqualTo(EntitlementStatus.ENTITLED);
    assertThat(entitlement.isEntitled()).isTrue();
    assertThat(entitlement.getGrantedAt()).isEqualTo(T2);
    assertThat(entitlement.getRevokedAt()).isNull();
  }
}
