package id.co.nativeapp.org.group;

/**
 * The kind of membership transition carried by a {@code GroupMembershipChanged} event — a company
 * was {@link #ADDED} to a consolidation group or {@link #REMOVED} from it. Emitted as its {@code
 * name()} in the event's {@code change_kind} field so a consumer can update its local group
 * read-model (add to / drop from the active member set).
 */
public enum GroupMembershipChangeKind {
  ADDED,
  REMOVED
}
