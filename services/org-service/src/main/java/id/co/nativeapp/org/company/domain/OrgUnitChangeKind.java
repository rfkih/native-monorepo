package id.co.nativeapp.org.company.domain;

/**
 * The kind of lifecycle change carried by an {@code OrgUnitChanged} event — what happened to the
 * node: it was {@link #RENAMED}, {@link #MOVED} to a new parent, {@link #DEACTIVATED}, or {@link
 * #REACTIVATED}. Emitted as its {@code name()} in the event's {@code change_kind} field (an Avro
 * {@code string}, so adding a kind is backward-compatible) so a consumer can react appropriately
 * (e.g. drop a deactivated node from a cached read model). Consumers that key on the event's {@code
 * active} flag rather than this string react to {@code DEACTIVATED}/{@code REACTIVATED} uniformly.
 */
public enum OrgUnitChangeKind {
  RENAMED,
  MOVED,
  DEACTIVATED,
  REACTIVATED
}
