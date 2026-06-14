package id.co.nativeapp.org.company;

/**
 * The kind of an {@link OrgUnit} in the company's org tree.
 *
 * <p>The first node created with a company (the "business") is a {@link #BUSINESS_UNIT}; branches,
 * outlets, and teams hang below it. Persisted as its {@code name()} via {@code EnumType.STRING}, so
 * the {@code org_unit.type} column is human-readable and stable against reordering.
 */
public enum OrgUnitType {
  BUSINESS_UNIT,
  BRANCH,
  OUTLET,
  TEAM;

  /**
   * Parses an {@code OrgUnitType} from a request string, accepting any case and trimming
   * whitespace.
   *
   * @param raw the wire value (e.g. {@code "business_unit"} / {@code "OUTLET"})
   * @throws IllegalArgumentException if {@code raw} is null/blank or not a known type (mapped to a
   *     {@code 400} by {@link id.co.nativeapp.org.config.ApiExceptionHandler})
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
