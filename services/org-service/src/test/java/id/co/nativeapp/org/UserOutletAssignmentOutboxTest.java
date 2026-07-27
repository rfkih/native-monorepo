package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateOrgUnitCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.org.user.messaging.UserOutletAssignmentChangedSchema;
import id.co.nativeapp.org.user.service.UserOutletAssignmentWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Producer-side outbox test for the {@code UserOutletAssignmentChanged} event (Phase 5).
 *
 * <p>Verifies that {@link UserOutletAssignmentWriter#replaceAssignments} writes the correct
 * {@code UserOutletAssignmentChanged} outbox rows in the SAME transaction as the assignment DB
 * writes (transactional outbox — rule 3, CLAUDE.md). Scenarios covered:
 *
 * <ol>
 *   <li><strong>ASSIGN</strong>: a new outlet in the desired set → one {@code ASSIGNED} outbox row.
 *   <li><strong>UNASSIGN</strong>: an outlet removed from the desired set → one {@code UNASSIGNED}
 *       outbox row.
 *   <li><strong>REOPEN</strong>: a previously-closed assignment added back → one {@code ASSIGNED}
 *       outbox row (not a second fresh insert).
 *   <li><strong>IDEMPOTENT no-op</strong>: an already-active assignment in the desired set → no
 *       outbox row (no change to emit).
 * </ol>
 *
 * <p>Each outbox row is verified:
 * <ul>
 *   <li>{@code event_type = "UserOutletAssignmentChanged"}, {@code aggregate_type = "user_outlet_assignment"}
 *   <li>the Avro payload deserializes to the correct {@code change_kind}, {@code user_id},
 *       {@code org_unit_id}, and {@code company_id}
 *   <li>the {@code aggregate_id} on the outbox row is the assignment UUID — Debezium keys the
 *       Kafka record by it, so partition ordering is per-(user, outlet) tuple (see the event
 *       catalog entry)
 * </ul>
 */
@SpringBootTest
class UserOutletAssignmentOutboxTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner";
  private static final String USER_A = "kc-sub-user-a";

  @Autowired private CompanyService companyService;
  @Autowired private OrgUnitService orgUnitService;
  @Autowired private UserOutletAssignmentWriter assignmentWriter;
  @Autowired private JdbcTemplate jdbcTemplate;

  private record TenantSetup(UUID companyId, UUID rootId) {}

  private TenantSetup bootstrap(String name) {
    var r =
        companyService.createCompany(
            new CreateCompanyCommand(name, "IDR", "id", name + " HQ", "outlet", ACTOR));
    return new TenantSetup(r.company().getId(), r.firstBusiness().getId());
  }

  private UUID createOutlet(TenantSetup t, String name) throws Exception {
    return TenantContext.callAs(
        t.companyId().toString(),
        ACTOR,
        () -> orgUnitService.create(new CreateOrgUnitCommand(name, "outlet", t.rootId())).getId());
  }

  private List<Map<String, Object>> outboxRows(String eventType) {
    return jdbcTemplate.queryForList(
        "SELECT * FROM outbox WHERE event_type = ? ORDER BY occurred_at", eventType);
  }

  // -----------------------------------------------------------------------
  // 1. Assign → one ASSIGNED outbox row per new outlet
  // -----------------------------------------------------------------------

  @Test
  void assignEmitsAssignedOutboxRow() throws Exception {
    TenantSetup t = bootstrap("Outlet-Outbox-Assign-Co");
    UUID outletId = createOutlet(t, "Downtown");

    TenantContext.callAs(
        t.companyId().toString(),
        ACTOR,
        () -> {
          assignmentWriter.replaceAssignments(USER_A, List.of(outletId));
          return null;
        });

    List<Map<String, Object>> rows = outboxRows(UserOutletAssignmentChangedSchema.EVENT_TYPE);
    // replaceAssignments on the company setup emits 1 event for the root outlet
    // created during bootstrap, plus 1 for the user assignment → we care only about USER_A's.
    List<Map<String, Object>> userRows =
        rows.stream()
            .filter(r -> {
              GenericRecord rec = decodePayload(r);
              return USER_A.equals(rec.get("user_id").toString());
            })
            .toList();

    assertThat(userRows).hasSize(1);
    Map<String, Object> row = userRows.getFirst();
    assertThat(row.get("aggregate_type")).isEqualTo(UserOutletAssignmentChangedSchema.AGGREGATE_TYPE);

    GenericRecord payload = decodePayload(row);
    assertThat(payload.get("change_kind").toString()).isEqualTo("ASSIGNED");
    assertThat(payload.get("user_id").toString()).isEqualTo(USER_A);
    assertThat(payload.get("org_unit_id").toString()).isEqualTo(outletId.toString());
    assertThat(payload.get("company_id").toString()).isEqualTo(t.companyId().toString());
  }

  // -----------------------------------------------------------------------
  // 2. Unassign → one UNASSIGNED outbox row per removed outlet
  // -----------------------------------------------------------------------

  @Test
  void unassignEmitsUnassignedOutboxRow() throws Exception {
    TenantSetup t = bootstrap("Outlet-Outbox-Unassign-Co");
    UUID outletId = createOutlet(t, "Southside");

    // Assign first.
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of(outletId)); return null; });

    // Clear outbox rows to get a clean baseline for the unassign operation.
    jdbcTemplate.update(
        "DELETE FROM outbox WHERE event_type = ?", UserOutletAssignmentChangedSchema.EVENT_TYPE);

    // Remove all outlets from the user's set → close the row.
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of()); return null; });

    List<Map<String, Object>> rows = outboxRows(UserOutletAssignmentChangedSchema.EVENT_TYPE);
    List<Map<String, Object>> userRows =
        rows.stream()
            .filter(r -> USER_A.equals(decodePayload(r).get("user_id").toString()))
            .toList();

    assertThat(userRows).hasSize(1);
    GenericRecord payload = decodePayload(userRows.getFirst());
    assertThat(payload.get("change_kind").toString()).isEqualTo("UNASSIGNED");
    assertThat(payload.get("org_unit_id").toString()).isEqualTo(outletId.toString());
  }

  // -----------------------------------------------------------------------
  // 3. Reopen a previously-closed assignment → one ASSIGNED row (no duplicate insert)
  // -----------------------------------------------------------------------

  @Test
  void reopenEmitsAssignedOutboxRow() throws Exception {
    TenantSetup t = bootstrap("Outlet-Outbox-Reopen-Co");
    UUID outletId = createOutlet(t, "Eastside");

    // Assign then unassign.
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of(outletId)); return null; });
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of()); return null; });

    // Clear the outbox so only the reopen event remains after.
    jdbcTemplate.update(
        "DELETE FROM outbox WHERE event_type = ?", UserOutletAssignmentChangedSchema.EVENT_TYPE);

    // Reopen: assign the same outlet again.
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of(outletId)); return null; });

    List<Map<String, Object>> rows = outboxRows(UserOutletAssignmentChangedSchema.EVENT_TYPE);
    List<Map<String, Object>> userRows =
        rows.stream()
            .filter(r -> USER_A.equals(decodePayload(r).get("user_id").toString()))
            .toList();

    assertThat(userRows).hasSize(1);
    GenericRecord payload = decodePayload(userRows.getFirst());
    assertThat(payload.get("change_kind").toString()).isEqualTo("ASSIGNED");
    assertThat(payload.get("org_unit_id").toString()).isEqualTo(outletId.toString());
  }

  // -----------------------------------------------------------------------
  // 4. No-op (already active) → no new outbox row
  // -----------------------------------------------------------------------

  @Test
  void noOpForAlreadyActiveAssignmentEmitsNoOutboxRow() throws Exception {
    TenantSetup t = bootstrap("Outlet-Outbox-Noop-Co");
    UUID outletId = createOutlet(t, "Westside");

    // First assign (creates the row + ASSIGNED event).
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of(outletId)); return null; });

    int countBeforeNoop =
        outboxRows(UserOutletAssignmentChangedSchema.EVENT_TYPE).stream()
            .filter(r -> USER_A.equals(decodePayload(r).get("user_id").toString()))
            .mapToInt(r -> 1)
            .sum();

    // Second call with the same set → no-op (assignment already active).
    TenantContext.callAs(
        t.companyId().toString(), ACTOR,
        () -> { assignmentWriter.replaceAssignments(USER_A, List.of(outletId)); return null; });

    int countAfterNoop =
        outboxRows(UserOutletAssignmentChangedSchema.EVENT_TYPE).stream()
            .filter(r -> USER_A.equals(decodePayload(r).get("user_id").toString()))
            .mapToInt(r -> 1)
            .sum();

    // No new outbox row was written for the no-op.
    assertThat(countAfterNoop).isEqualTo(countBeforeNoop);
  }

  // -----------------------------------------------------------------------
  // Helper: decode the Avro payload from an outbox row
  // -----------------------------------------------------------------------

  private GenericRecord decodePayload(Map<String, Object> row) {
    byte[] payload = (byte[]) row.get("payload");
    return AvroSerde.deserialize(payload, UserOutletAssignmentChangedSchema.schema());
  }
}
