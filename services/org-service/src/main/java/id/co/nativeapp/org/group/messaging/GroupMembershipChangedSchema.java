package id.co.nativeapp.org.group.messaging;

import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.domain.GroupMembershipChangeKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code GroupMembershipChanged} Avro schema from the classpath ({@code
 * avro/GroupMembershipChanged.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation plugin (same approach as {@link
 * id.co.nativeapp.org.company.messaging.OrgUnitChangedSchema}). The schema is the single source of
 * truth registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects a
 * {@link GroupMembership} plus the {@link GroupMembershipChangeKind} onto it.
 *
 * <p>{@code effective_from} / {@code effective_to} are Avro {@code date} logical-type ints (epoch
 * day), exactly as {@code AssignmentChanged} carries its effective dates; the far-future {@code
 * 9999-12-31} sentinel marks an open membership.
 */
public final class GroupMembershipChangedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/GroupMembershipChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "GroupMembershipChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "consolidation_group";

  private static final Schema SCHEMA = parse();

  private GroupMembershipChangedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code GroupMembershipChanged}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code GroupMembershipChanged} {@link GenericRecord} reflecting the membership's state
   * after the given kind of transition. The partition key is {@code group_id} (the aggregate id).
   *
   * @param membership the membership in its post-change state
   * @param changeKind the transition (ADDED on a new active row, REMOVED once the window is closed)
   */
  public static GenericRecord toRecord(
      GroupMembership membership, GroupMembershipChangeKind changeKind) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("group_id", membership.getGroupId().toString());
    record.put("member_company_id", membership.getMemberCompanyId().toString());
    record.put("change_kind", changeKind.name());
    // Avro date logical type = epoch day as an int (matches AssignmentChanged's effective dates).
    record.put("effective_from", (int) membership.getEffectiveFrom().toEpochDay());
    record.put("effective_to", (int) membership.getEffectiveTo().toEpochDay());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        GroupMembershipChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
