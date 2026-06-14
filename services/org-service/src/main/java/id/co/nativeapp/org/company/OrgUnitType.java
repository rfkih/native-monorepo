package id.co.nativeapp.org.company;

import java.util.Optional;

/**
 * The kind of an {@link OrgUnit} in the company's self-referencing org tree, and the source of
 * truth for the <strong>allowed parent → child hierarchy</strong>.
 *
 * <p>The tree is strictly nested: {@code business_unit > branch > outlet > team}. Each level has
 * exactly one legal parent type (and {@link #BUSINESS_UNIT} is the root, with no parent), so the
 * hierarchy is enforced structurally rather than by scattered {@code if} checks:
 *
 * <ul>
 *   <li>{@link #BUSINESS_UNIT} — a top-level node; its parent MUST be {@code null}.
 *   <li>{@link #BRANCH} — hangs under a {@code BUSINESS_UNIT}.
 *   <li>{@link #OUTLET} — hangs under a {@code BRANCH}.
 *   <li>{@link #TEAM} — hangs under an {@code OUTLET} (the leaf level).
 * </ul>
 *
 * <p>So a {@code TEAM} directly under a {@code BUSINESS_UNIT} (skipping {@code BRANCH}/{@code
 * OUTLET}) is rejected — the model forbids skipping levels. Persisted as its {@code name()} via
 * {@code EnumType.STRING}, so the {@code org_unit.type} column is human-readable and stable against
 * reordering.
 */
public enum OrgUnitType {
  BUSINESS_UNIT,
  BRANCH,
  OUTLET,
  TEAM;

  /**
   * The single legal parent type for this kind, or {@link Optional#empty()} for a root type ({@link
   * #BUSINESS_UNIT}), which has no parent. This is the one place the {@code business_unit > branch
   * > outlet > team} nesting is encoded.
   */
  public Optional<OrgUnitType> requiredParentType() {
    return switch (this) {
      case BUSINESS_UNIT -> Optional.empty();
      case BRANCH -> Optional.of(BUSINESS_UNIT);
      case OUTLET -> Optional.of(BRANCH);
      case TEAM -> Optional.of(OUTLET);
    };
  }

  /** {@code true} if this is a top-level (root) type — i.e. it must have a {@code null} parent. */
  public boolean isRoot() {
    return requiredParentType().isEmpty();
  }

  /**
   * Whether a node of this type may legally sit directly under a parent of {@code parentType} (or,
   * when {@code parentType} is {@code null}, at the top level). Encapsulates the full parent→child
   * rule so callers never re-derive it.
   *
   * @param parentType the prospective parent's type, or {@code null} for a top-level placement
   */
  public boolean canBeChildOf(OrgUnitType parentType) {
    return requiredParentType()
        .map(required -> required == parentType)
        .orElseGet(() -> parentType == null);
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
