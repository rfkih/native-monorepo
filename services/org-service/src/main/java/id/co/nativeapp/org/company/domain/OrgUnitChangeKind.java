package id.co.nativeapp.org.company.domain;

/**
 * The kind of lifecycle change carried by an {@code OrgUnitChanged} event — what happened to the
 * node: it was {@link #RENAMED}, {@link #MOVED} to a new parent, or {@link #DEACTIVATED}. Emitted
 * as its {@code name()} in the event's {@code change_kind} field so a consumer can react
 * appropriately (e.g. drop a deactivated node from a cached read model).
 */
public enum OrgUnitChangeKind {
  RENAMED,
  MOVED,
  DEACTIVATED
}
