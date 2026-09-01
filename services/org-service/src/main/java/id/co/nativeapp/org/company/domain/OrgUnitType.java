package id.co.nativeapp.org.company.domain;

/**
 * The kind of an {@link OrgUnit}. Since ADR 0070 there is exactly ONE: {@link #OUTLET} — a
 * company's org "tree" is a flat list of physical selling locations hanging directly off the
 * company, with no nesting at all ({@code company > outlet}).
 *
 * <p><strong>Why the enum survives at all.</strong> A single-valued enum looks redundant, and it is
 * — deliberately. The {@code org_unit.type} column and the {@code type} field on {@code
 * OrgUnitCreated} / {@code OrgUnitChanged} / {@code OrgUnitDeleted} are load-bearing wire/state
 * shape that downstream read models ({@code finance.org_unit_ref}, {@code
 * employee.org_unit_projection}) already store. Keeping the type as a real value — always {@code
 * "OUTLET"} — meant ADR 0070 changed NO event schema and needed NO consumer migration (rule 7).
 * Removing it would be a breaking change bought for nothing.
 *
 * <p><strong>What went away (ADR 0070).</strong> {@code BUSINESS_UNIT} (the console's "Division")
 * and {@code TEAM}, along with the whole parent→child rule machinery this enum used to own — {@code
 * allowedParentTypes} / {@code canBeChildOf} / {@code describeAllowedParents} / {@code isRoot}.
 * With one level there is no hierarchy to validate: an outlet's parent is ALWAYS {@code null},
 * enforced in the {@link OrgUnit} aggregate. Grouping outlets for reporting is served by
 * multi-company ownership (ADR 0021) plus group consolidation, not by a tree level.
 *
 * <p>Persisted as its {@code name()} via {@code EnumType.STRING}, so the column stays
 * human-readable and stable against reordering.
 */
public enum OrgUnitType {
  /** A physical selling location, hanging directly off the company. The only kind there is. */
  OUTLET;

  /**
   * Parses an {@code OrgUnitType} from a request string, accepting any case and trimming
   * whitespace.
   *
   * <p>Since ADR 0070 the only accepted value is {@code outlet}. A request still naming a removed
   * level ({@code business_unit} / {@code team}) fails here with a {@code 400} rather than silently
   * creating something else — an old client gets a clear error, not a surprise.
   *
   * @param raw the wire value (e.g. {@code "outlet"} / {@code "OUTLET"})
   * @throws IllegalArgumentException if {@code raw} is null/blank or not a known type (mapped to a
   *     {@code 400} by {@link id.co.nativeapp.security.ApiExceptionHandler})
   */
  public static OrgUnitType from(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("org unit type must not be blank");
    }
    try {
      return OrgUnitType.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown org unit type: " + raw + " (the only type is 'outlet' — ADR 0070)");
    }
  }
}
