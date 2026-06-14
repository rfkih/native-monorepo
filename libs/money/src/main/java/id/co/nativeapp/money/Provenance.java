package id.co.nativeapp.money;

/**
 * Where an {@link FxRate} came from — its trust level.
 *
 * <ul>
 *   <li>{@link #STUB} — a deliberately-fake, illustrative seed rate. A converted figure derived
 *       from a STUB rate is NOT authoritative and must be flagged as such (the {@code uses_stub_fx}
 *       runtime flag), exactly as the payroll engine flags {@code uses_illustrative_rules}. NEVER
 *       present a STUB-derived amount as official.
 *   <li>{@link #LIVE} — a rate sourced from a live provider feed (not yet officially reconciled).
 *   <li>{@link #OFFICIAL} — an authoritative, reconciled rate (e.g. a central-bank middle rate).
 * </ul>
 *
 * <p>The stub→live cutover is purely DATA + config: a new effective-dated row with a non-STUB
 * provenance shadows the seed, and conversions stop being flagged automatically. No conversion-path
 * code changes.
 */
public enum Provenance {
  /** Deliberately-fake illustrative seed — a converted figure must be flagged provisional. */
  STUB,
  /** A live-provider feed rate. */
  LIVE,
  /** An authoritative, reconciled rate. */
  OFFICIAL
}
