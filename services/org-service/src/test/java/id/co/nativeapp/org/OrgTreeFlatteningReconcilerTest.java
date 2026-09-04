package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.messaging.OrgUnitDeletedSchema;
import id.co.nativeapp.org.company.service.OrgTreeFlatteningReconciler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0070 — the org-tree flattening reconciler, run against a REAL pre-flattening tree.
 *
 * <p><strong>Why this test exists.</strong> Every other test in this suite builds its fixture
 * through the current API, which can only ever produce flat {@code OUTLET} rows — so none of them
 * exercises the one situation the reconciler was written for: a tenant whose {@code org_unit} table
 * still holds {@code BUSINESS_UNIT} / {@code TEAM} rows and parented outlets, written by an image
 * that predates ADR 0070. This class seeds exactly that shape over the admin (BYPASSRLS) connection
 * and drives the reconciler across it.
 *
 * <p>It is the regression guard for a bug that made the whole migration a no-op: with the legacy
 * constants absent from {@code OrgUnitType}, Hibernate could not hydrate a legacy row at all, so
 * {@code findAll()} threw, the reconciler logged a failure and left the tenant queued FOREVER —
 * silently, on every boot, for exactly the tenants that needed flattening.
 */
@SpringBootTest
class OrgTreeFlatteningReconcilerTest extends PostgresRlsTestBase {

  @Autowired private OrgTreeFlatteningReconciler reconciler;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flattensALegacyTreeRetiringTheDivisionAndTeamAndEmittingOneEventPerNode() throws Exception {
    UUID companyId = UUID.randomUUID();
    UUID divisionId = UUID.randomUUID();
    UUID outletId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();

    seedLegacyTree(companyId, divisionId, outletId, teamId);
    queueForFlattening(companyId);

    reconciler.onApplicationReady();

    // The division and the team are GONE; the outlet survives, lifted to the top level.
    assertThat(typeOf(divisionId)).isNull();
    assertThat(typeOf(teamId)).isNull();
    assertThat(typeOf(outletId)).isEqualTo("OUTLET");
    assertThat(parentOf(outletId)).isNull();

    // One OrgUnitChanged/MOVED for the reparented outlet.
    List<Map<String, Object>> moved = outboxRows("OrgUnitChanged", companyId);
    assertThat(moved).hasSize(1);
    GenericRecord movedEvent =
        AvroSerde.deserialize((byte[]) moved.get(0).get("payload"), OrgUnitChangedSchema.schema());
    assertThat(movedEvent.get("org_unit_id").toString()).isEqualTo(outletId.toString());
    assertThat(movedEvent.get("change_kind").toString()).isEqualTo("MOVED");
    assertThat(movedEvent.get("parent_id")).isNull();

    // One OrgUnitDeleted per retired node, so finance/employee purge their cached refs.
    List<Map<String, Object>> deleted = outboxRows("OrgUnitDeleted", companyId);
    assertThat(deleted).hasSize(2);
    assertThat(deleted.stream().map(r -> r.get("aggregate_id")))
        .containsExactlyInAnyOrder(divisionId.toString(), teamId.toString());
    GenericRecord divisionEvent =
        AvroSerde.deserialize(
            (byte[])
                deleted.stream()
                    .filter(r -> divisionId.toString().equals(r.get("aggregate_id")))
                    .findFirst()
                    .orElseThrow()
                    .get("payload"),
            OrgUnitDeletedSchema.schema());
    assertThat(divisionEvent.get("type").toString()).isEqualTo("BUSINESS_UNIT");

    // The tenant is marked done, so the next boot is a no-op.
    assertThat(pendingCount()).isZero();
  }

  @Test
  void isIdempotentAcrossRepeatedBoots() throws Exception {
    UUID companyId = UUID.randomUUID();
    UUID divisionId = UUID.randomUUID();
    UUID outletId = UUID.randomUUID();
    UUID teamId = UUID.randomUUID();

    seedLegacyTree(companyId, divisionId, outletId, teamId);
    queueForFlattening(companyId);

    reconciler.onApplicationReady();
    int eventsAfterFirstRun =
        outboxRows("OrgUnitChanged", companyId).size()
            + outboxRows("OrgUnitDeleted", companyId).size();

    // A second boot finds an empty queue and must emit nothing further.
    reconciler.onApplicationReady();
    int eventsAfterSecondRun =
        outboxRows("OrgUnitChanged", companyId).size()
            + outboxRows("OrgUnitDeleted", companyId).size();

    assertThat(eventsAfterFirstRun).isEqualTo(3);
    assertThat(eventsAfterSecondRun).isEqualTo(eventsAfterFirstRun);
    assertThat(typeOf(outletId)).isEqualTo("OUTLET");
  }

  @Test
  void aTenantWhoseTreeIsAlreadyFlatIsAHarmlessNoop() throws Exception {
    UUID companyId = UUID.randomUUID();
    UUID outletId = UUID.randomUUID();

    seedOrgUnit(companyId, outletId, "OUTLET", null, "Already Flat");
    queueForFlattening(companyId);

    reconciler.onApplicationReady();

    assertThat(typeOf(outletId)).isEqualTo("OUTLET");
    assertThat(outboxRows("OrgUnitChanged", companyId)).isEmpty();
    assertThat(outboxRows("OrgUnitDeleted", companyId)).isEmpty();
    assertThat(pendingCount()).isZero();
  }

  // ---- fixtures (written over the admin BYPASSRLS connection: this is pre-ADR-0070 shape that
  // ---- the current API deliberately cannot produce) --------------------------------------------

  private void seedLegacyTree(UUID companyId, UUID divisionId, UUID outletId, UUID teamId)
      throws Exception {
    seedOrgUnit(companyId, divisionId, "BUSINESS_UNIT", null, "Bara Kebab");
    seedOrgUnit(companyId, outletId, "OUTLET", divisionId, "Bara Kebab Binagriya");
    seedOrgUnit(companyId, teamId, "TEAM", outletId, "Kitchen");
  }

  private void seedOrgUnit(UUID companyId, UUID id, String type, UUID parentId, String name)
      throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                """
                INSERT INTO org_unit
                  (id, name, type, parent_id, legal_employer_id, active,
                   effective_from, effective_to,
                   created_at, created_by, updated_at, updated_by, version, company_id)
                VALUES (?, ?, ?, ?, ?, true, CURRENT_DATE, DATE '9999-12-31',
                        ?, 'legacy', ?, 'legacy', 0, ?)
                """)) {
      ps.setObject(1, id);
      ps.setString(2, name);
      ps.setString(3, type);
      ps.setObject(4, parentId);
      ps.setObject(5, companyId);
      ps.setObject(6, java.sql.Timestamp.from(Instant.now()));
      ps.setObject(7, java.sql.Timestamp.from(Instant.now()));
      ps.setString(8, companyId.toString());
      ps.executeUpdate();
    }
  }

  /** Puts the tenant on the V15 work queue exactly as the migration's discovery would. */
  private void queueForFlattening(UUID companyId) {
    jdbcTemplate.update(
        "INSERT INTO org_tree_flattening_work (company_id) VALUES (?)"
            + " ON CONFLICT (company_id) DO NOTHING",
        companyId.toString());
  }

  private String typeOf(UUID id) throws Exception {
    return (String) scalar("SELECT type FROM org_unit WHERE id = ?", id);
  }

  private UUID parentOf(UUID id) throws Exception {
    return (UUID) scalar("SELECT parent_id FROM org_unit WHERE id = ?", id);
  }

  private Object scalar(String sql, UUID id) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1) : null;
      }
    }
  }

  private List<Map<String, Object>> outboxRows(String eventType, UUID companyId) {
    return jdbcTemplate.queryForList(
        "SELECT aggregate_id, payload FROM outbox WHERE event_type = ? AND company_id = ?"
            + " ORDER BY occurred_at, id",
        eventType,
        companyId);
  }

  private int pendingCount() {
    Integer n =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM org_tree_flattening_work WHERE done_at IS NULL", Integer.class);
    return n == null ? 0 : n;
  }
}
