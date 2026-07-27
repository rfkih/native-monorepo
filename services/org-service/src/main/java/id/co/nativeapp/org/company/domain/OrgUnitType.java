package id.co.nativeapp.org.company.domain;

import java.util.Set;

/**
 * The kind of an {@link OrgUnit} in the company's self-referencing org tree, and the source of
 * truth for the <strong>allowed parent → child hierarchy</strong>.
 *
 * <p>The tree nests {@code business_unit > outlet > team} (ADR 0012 — the tree is flat: an outlet
 * IS the physical selling location; there is no intermediate branch level):
 *
 * <ul>
 *   <li>{@link #BUSINESS_UNIT} — a top-level node; its parent MUST be {@code null}.
 *   <li>{@link #OUTLET} — hangs under a {@code BUSINESS_UNIT}.
 *   <li>{@link #TEAM} — hangs under an {@code OUTLET} (the leaf level).
 * </ul>
 *
 * <p>So a {@code TEAM} directly under a {@code BUSINESS_UNIT} is rejected — a team belongs to a
 * physical outlet. Persisted as its {@code name()} via {@code EnumType.STRING}, so the {@code
 * org_unit.type} column is human-readable and stable against reordering.
 */
public enum OrgUnitType {
  BUSINESS_UNIT,
  OUTLET,
  TEAM;

  /**
   * The legal parent types for this kind — empty for a root type ({@link #BUSINESS_UNIT}), which
   * has no parent. This is the one place the nesting is encoded.
   */
  public Set<OrgUnitType> allowedParentTypes() {
    return switch (this) {
      case BUSINESS_UNIT -> Set.of();
      case OUTLET -> Set.of(BUSINESS_UNIT);
      case TEAM -> Set.of(OUTLET);
    };
  }

  /** {@code true} if this is a top-level (root) type — i.e. it must have a {@code null} parent. */
  public boolean isRoot() {
    return allowedParentTypes().isEmpty();
  }

  /**
   * Whether a node of this type may legally sit directly under a parent of {@code parentType} (or,
   * when {@code parentType} is {@code null}, at the top level). Encapsulates the full parent→child
   * rule so callers never re-derive it.
   *
   * @param parentType the prospective parent's type, or {@code null} for a top-level placement
   */
  public boolean canBeChildOf(OrgUnitType parentType) {
    if (parentType == null) {
      return isRoot();
    }
    return allowedParentTypes().contains(parentType);
  }

  /**
   * A human-readable list of this type's legal parents (e.g. {@code "BUSINESS_UNIT"}) for error
   * messages; callers must not invoke it on a root type.
   */
  public String describeAllowedParents() {
    return String.join(" or ", allowedParentTypes().stream().map(Enum::name).sorted().toList());
  }

  /**
   * Parses an {@code OrgUnitType} from a request string, accepting any case and trimming
   * whitespace.
   *
   * @param raw the wire value (e.g. {@code "business_unit"} / {@code "OUTLET"})
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
      throw new IllegalArgumentException("Unknown org unit type: " + raw);
    }
  }
}
