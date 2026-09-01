package id.co.nativeapp.org.company.domain;

import java.util.Set;

/**
 * The kind of an {@link OrgUnit}. Since ADR 0070 a company's org "tree" is a flat list of physical
 * selling locations hanging directly off the company ({@code company > outlet}), so {@link #OUTLET}
 * is the only kind anything may be CREATED as.
 *
 * <p><strong>Why the retired constants are still here.</strong> {@link #BUSINESS_UNIT} (the
 * console's old "Division") and {@link #TEAM} are gone from the product, but they are NOT gone from
 * the database: a tenant written by a pre-ADR-0070 image still has such rows until the {@code
 * OrgTreeFlatteningReconciler} retires them. {@code OrgUnit.type} is mapped {@code
 * EnumType.STRING}, so Hibernate resolves the column through {@code Enum.valueOf} — deleting these
 * constants makes a legacy row IMPOSSIBLE TO LOAD, which means the reconciler's own {@code findAll}
 * throws and the migration silently never runs on exactly the tenants that need it. They stay until
 * the contract migration that deletes the last such row.
 *
 * <p><strong>They are unreachable from every write path.</strong> {@link #from(String)} — the only
 * way a request string becomes a type — rejects them, so no new row can ever be created with one;
 * {@link #isRetired()} names the distinction for anything else that needs it. The enum is
 * deliberately NOT reduced to a single constant: that reduction is what caused the bug above.
 *
 * <p>What DID go away is the hierarchy: there are no parent→child rules, because an outlet's parent
 * is always {@code null} (enforced in the {@link OrgUnit} aggregate). Grouping outlets for
 * reporting is served by multi-company ownership (ADR 0021) plus group consolidation, not by a tree
 * level.
 */
public enum OrgUnitType {

  /** A physical selling location, hanging directly off the company. The only creatable kind. */
  OUTLET,

  /**
   * RETIRED (ADR 0070) — the old "Division". Persists only until the reconciler deletes the row.
   */
  BUSINESS_UNIT,

  /**
   * RETIRED (ADR 0070) — a group inside an outlet. Persists only until the reconciler deletes it.
   */
  TEAM;

  /** The kinds ADR 0070 removed: loadable from a legacy row, never creatable. */
  private static final Set<OrgUnitType> RETIRED = Set.of(BUSINESS_UNIT, TEAM);

  /**
   * Whether this kind was retired by ADR 0070 and exists only so a pre-flattening row can still be
   * read (and then deleted). Never {@code true} for anything the current API can create.
   */
  public boolean isRetired() {
    return RETIRED.contains(this);
  }

  /**
   * Parses an {@code OrgUnitType} from a request string, accepting any case and trimming
   * whitespace.
   *
   * <p>Since ADR 0070 the only accepted value is {@code outlet}. A request naming a RETIRED level
   * ({@code business_unit} / {@code team}) is rejected here with a {@code 400} — the constants
   * exist for reading legacy rows, not for creating new ones — so an old client gets a clear error
   * rather than silently creating a node the flat model cannot represent.
   *
   * @param raw the wire value (e.g. {@code "outlet"} / {@code "OUTLET"})
   * @throws IllegalArgumentException if {@code raw} is null/blank, unknown, or a retired kind
   *     (mapped to a {@code 400} by {@link id.co.nativeapp.security.ApiExceptionHandler})
   */
  public static OrgUnitType from(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("org unit type must not be blank");
    }
    OrgUnitType parsed;
    try {
      parsed = OrgUnitType.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown org unit type: " + raw + " (the only type is 'outlet' — ADR 0070)");
    }
    if (parsed.isRetired()) {
      throw new IllegalArgumentException(
          "Org unit type "
              + raw
              + " was removed by ADR 0070 — the org tree is flat (company > outlet)");
    }
    return parsed;
  }
}
