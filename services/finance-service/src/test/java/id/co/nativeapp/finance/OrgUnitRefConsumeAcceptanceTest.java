package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.orgref.domain.OrgUnitRef;
import id.co.nativeapp.finance.orgref.repository.OrgUnitRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2 outlet-scoping — the finance {@code org_unit_ref} CONSUMER acceptance + IDEMPOTENCY +
 * cross-tenant ISOLATION proof. finance consumes the org-service {@code OrgUnitCreated} / {@code
 * OrgUnitChanged} events (raw Avro bytes off a real Kafka broker) into its local {@code
 * org_unit_ref} read model, and a re-delivery of either event is a clean no-op (dedupe by event
 * UUID via {@code ProcessedEventStore}).
 *
 * <p>Acceptance criteria:
 *
 * <ol>
 *   <li>{@code OrgUnitCreated} lands and a matching {@code org_unit_ref} row appears.
 *   <li>{@code OrgUnitChanged} (rename) updates the row's name idempotently; re-delivery is a
 *       no-op.
 *   <li>{@code OrgUnitChanged} (deactivate) sets {@code active = false}; re-delivery is a no-op.
 *   <li>An event for tenant A does NOT appear in tenant B's view (cross-tenant isolation via RLS).
 * </ol>
 */
@SpringBootTest
@Import(OrgUnitRefConsumeAcceptanceTest.OrgUnitRefReader.class)
class OrgUnitRefConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String ACTOR = "finance-consumer-test";

  @Autowired private OrgUnitRefReader orgUnitRefReader;

  @Test
  void orgUnitCreatedLandsInTheRefTable() {
    UUID orgUnitId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(orgUnitId, companyId, "outlet", null, "Main Outlet");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, eventId, created);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<OrgUnitRef> ref =
                  TenantContext.callAs(
                      companyId.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId));
              assertThat(ref).isPresent();
              assertThat(ref.get().getName()).isEqualTo("Main Outlet");
              assertThat(ref.get().getType()).isEqualTo("outlet");
              assertThat(ref.get().isActive()).isTrue();
            });
  }

  @Test
  void orgUnitCreatedIsIdempotentOnRedelivery() {
    UUID orgUnitId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(
            orgUnitId, companyId, "outlet", null, "Idempotent Outlet");
    // deliver twice with the same event id
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, eventId, created);
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, eventId, created);

    // Use a marker to drain the partition (so the duplicate is necessarily processed).
    UUID markerOrgUnitId = UUID.randomUUID();
    UUID markerEventId = UUID.randomUUID();
    var marker =
        OrgUnitRefEventFixtures.orgUnitCreated(
            markerOrgUnitId, companyId, "outlet", null, "Marker");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), markerOrgUnitId, markerEventId, marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<OrgUnitRef> markerRef =
                  TenantContext.callAs(
                      companyId.toString(),
                      ACTOR,
                      () -> orgUnitRefReader.findById(markerOrgUnitId));
              assertThat(markerRef).isPresent();
            });

    // Exactly one row for the original org unit (no duplicate created by re-delivery).
    long rowCount = orgUnitRefRowCountAsAdmin(orgUnitId);
    assertThat(rowCount).as("re-delivery must not create a duplicate row").isEqualTo(1L);
  }

  @Test
  void orgUnitChangedRenameAppliedIdempotently() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();

    // First: create the org unit.
    UUID createdEventId = UUID.randomUUID();
    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(orgUnitId, companyId, "outlet", null, "Old Name");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, createdEventId, created);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            companyId.toString(),
                            ACTOR,
                            () -> orgUnitRefReader.findById(orgUnitId)))
                    .isPresent());

    // Then: rename — delivered TWICE with the same event id.
    UUID changedEventId = UUID.randomUUID();
    var changed =
        OrgUnitRefEventFixtures.orgUnitChanged(
            orgUnitId, companyId, "outlet", null, "RENAMED", "New Name", true);
    OrgUnitRefEventFixtures.publishChanged(
        KAFKA.getBootstrapServers(), orgUnitId, changedEventId, changed);
    OrgUnitRefEventFixtures.publishChanged(
        KAFKA.getBootstrapServers(), orgUnitId, changedEventId, changed);

    // Await the EXPECTED STATE directly (a cross-topic "drain marker" gives no ordering
    // guarantee — the created and changed topics are consumed independently). The duplicate
    // delivery can neither change the state again nor create a second row, so the
    // idempotency assertions below hold regardless of when it lands.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                                companyId.toString(),
                                ACTOR,
                                () -> orgUnitRefReader.findById(orgUnitId))
                            .map(OrgUnitRef::getName))
                    .contains("New Name"));

    // Exactly one row exists (no duplicates from re-delivery), still active.
    Optional<OrgUnitRef> ref =
        TenantContext.callAs(
            companyId.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId));
    assertThat(ref).isPresent();
    assertThat(ref.get().isActive()).isTrue();
    assertThat(orgUnitRefRowCountAsAdmin(orgUnitId)).isEqualTo(1L);
  }

  @Test
  void orgUnitChangedDeactivateAppliedIdempotently() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    UUID companyId = UUID.randomUUID();

    // Create.
    UUID createdEventId = UUID.randomUUID();
    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(
            orgUnitId, companyId, "outlet", null, "Active Outlet");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, createdEventId, created);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            companyId.toString(),
                            ACTOR,
                            () -> orgUnitRefReader.findById(orgUnitId)))
                    .isPresent());

    // Deactivate — delivered TWICE with the same event id.
    UUID deactivatedEventId = UUID.randomUUID();
    var deactivated =
        OrgUnitRefEventFixtures.orgUnitChanged(
            orgUnitId, companyId, "outlet", null, "DEACTIVATED", "Active Outlet", false);
    OrgUnitRefEventFixtures.publishChanged(
        KAFKA.getBootstrapServers(), orgUnitId, deactivatedEventId, deactivated);
    OrgUnitRefEventFixtures.publishChanged(
        KAFKA.getBootstrapServers(), orgUnitId, deactivatedEventId, deactivated);

    // Await the deactivated STATE directly (see the rename test — cross-topic markers give
    // no ordering guarantee; the duplicate cannot flip the state back or add a row).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                                companyId.toString(),
                                ACTOR,
                                () -> orgUnitRefReader.findById(orgUnitId))
                            .map(OrgUnitRef::isActive))
                    .as("deactivation must set active = false")
                    .contains(false));

    assertThat(orgUnitRefRowCountAsAdmin(orgUnitId)).isEqualTo(1L);
  }

  @Test
  void crossTenantIsolation_orgUnitIsNotVisibleToAnotherTenant() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(
            orgUnitId, tenantA, "outlet", null, "Tenant A Outlet");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, UUID.randomUUID(), created);

    // Wait until tenant A can see the row.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            tenantA.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId)))
                    .isPresent());

    // Tenant B must NOT see tenant A's org unit (RLS policy on org_unit_ref).
    Optional<OrgUnitRef> tenantBView =
        TenantContext.callAs(tenantB.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId));
    assertThat(tenantBView)
        .as("org_unit_ref is RLS-scoped; tenant B must not see tenant A's row")
        .isEmpty();
  }

  @Test
  void crossTenantIdCollisionFailsClosedAndNeverRelabels() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();

    var created =
        OrgUnitRefEventFixtures.orgUnitCreated(
            orgUnitId, tenantA, "outlet", null, "Tenant A Outlet");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, UUID.randomUUID(), created);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            tenantA.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId)))
                    .isPresent());

    // Tenant B publishes an event REUSING tenant A's org_unit_id — the negligible-probability
    // cross-tenant collision, pinned here. The upsert targets the tenant-composite unique
    // constraint, so tenant B's insert must trip the bare-PK violation and fail closed
    // (retries -> DLT); it must NEVER "DO UPDATE" (relabel) tenant A's row.
    var colliding =
        OrgUnitRefEventFixtures.orgUnitCreated(
            orgUnitId, tenantB, "outlet", null, "Tenant B Impostor");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), orgUnitId, UUID.randomUUID(), colliding);

    // Drain via a later tenant-B marker: once it lands, the collider has been dispositioned.
    UUID markerId = UUID.randomUUID();
    var marker =
        OrgUnitRefEventFixtures.orgUnitCreated(
            markerId, tenantB, "outlet", null, "Collision Marker");
    OrgUnitRefEventFixtures.publishCreated(
        KAFKA.getBootstrapServers(), markerId, UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                            tenantB.toString(), ACTOR, () -> orgUnitRefReader.findById(markerId)))
                    .isPresent());

    Optional<OrgUnitRef> tenantARow =
        TenantContext.callAs(tenantA.toString(), ACTOR, () -> orgUnitRefReader.findById(orgUnitId));
    assertThat(tenantARow).isPresent();
    assertThat(tenantARow.get().getName())
        .as("a cross-tenant id collision must never relabel the first tenant's row")
        .isEqualTo("Tenant A Outlet");
    assertThat(orgUnitRefRowCountAsAdmin(orgUnitId))
        .as("the colliding insert must fail closed — exactly one physical row")
        .isEqualTo(1L);
  }

  // ------------------------------------------------------------------ helpers

  private long orgUnitRefRowCountAsAdmin(UUID orgUnitId) {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM org_unit_ref WHERE org_unit_id = ?")) {
      ps.setObject(1, orgUnitId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A tiny {@code @Transactional} read helper so the auto-RLS aspect sets the tenant GUC. */
  @org.springframework.stereotype.Component
  public static class OrgUnitRefReader {

    private final OrgUnitRefRepository orgUnitRefRepository;

    public OrgUnitRefReader(OrgUnitRefRepository orgUnitRefRepository) {
      this.orgUnitRefRepository = orgUnitRefRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<OrgUnitRef> findById(UUID orgUnitId) {
      return orgUnitRefRepository.findById(orgUnitId);
    }
  }
}
