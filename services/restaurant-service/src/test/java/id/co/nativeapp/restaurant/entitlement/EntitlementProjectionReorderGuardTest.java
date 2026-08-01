package id.co.nativeapp.restaurant.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionReader;
import id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Bug-audit FIX 3 regression (V20): {@link EntitlementProjectionService}'s set-if-newer guard must
 * survive reordering between the two independently-lagging {@code EntitlementGranted}/{@code
 * EntitlementRevoked} topics. Idempotency (dedupe by event id, {@code ProcessedEventStore}) alone
 * does NOT protect against this — a reordered delivery carries a DIFFERENT event id, not a
 * re-delivery of one already seen, so it sails straight past the dedupe check; only the {@code
 * event_occurred_at} comparison in {@code EntitlementProjectionRepository#upsertSetIfNewer} stops
 * it from regressing the projection.
 */
@SpringBootTest
class EntitlementProjectionReorderGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String MODULE = "self_order";
  private static final String ACTOR = "test-actor";

  @Autowired private EntitlementProjectionService projectionService;
  @Autowired private EntitlementProjectionReader reader;

  @Test
  void aLaggingRedeliveredGrantWithAnEarlierStampNeverReopensAGateALaterRevokeClosed()
      throws Exception {
    Instant grantedAt = Instant.parse("2026-07-31T01:00:00Z");
    Instant revokedAt = Instant.parse("2026-07-31T02:00:00Z"); // later than grantedAt

    // The Revoked (later stamp) is applied first — the gate closes.
    boolean revokeApplied =
        projectionService.apply(
            new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, MODULE, false, revokedAt));
    assertThat(revokeApplied).isTrue();
    assertThat(isEntitled()).isFalse();

    // A lagging/redelivered Granted with an EARLIER stamp arrives afterwards, carrying a
    // DIFFERENT event id (not a duplicate of anything already processed) — idempotency alone
    // would NOT stop this from applying; only the set-if-newer guard must.
    boolean grantApplied =
        projectionService.apply(
            new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, MODULE, true, grantedAt));
    assertThat(grantApplied).isTrue(); // first delivery of THIS event id — not deduped

    // The stale grant must not have re-opened the gate: the row stays REVOKED.
    assertThat(isEntitled()).isFalse();
  }

  @Test
  void anEventWithTheSameStampAsStoredStillApplies() throws Exception {
    Instant occurredAt = Instant.parse("2026-07-31T03:00:00Z");

    projectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, MODULE, true, occurredAt));
    assertThat(isEntitled()).isTrue();

    // A same-timestamp Revoked (occurredAt == stored) is "not older than stored" (>=), so it must
    // still apply — the guard is inclusive, not a strict "newer than".
    boolean applied =
        projectionService.apply(
            new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, MODULE, false, occurredAt));
    assertThat(applied).isTrue();
    assertThat(isEntitled()).isFalse();
  }

  private boolean isEntitled() throws Exception {
    return TenantContext.callAs(TENANT, ACTOR, () -> reader.isEntitled(MODULE));
  }
}
