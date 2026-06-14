package id.co.nativeapp.employee.payroll;

/**
 * The provenance of a {@link StatutoryRule}'s figures — the first of the three-layer illustrative
 * flagging (HR-9). A rule is either an {@code ILLUSTRATIVE_PLACEHOLDER} (deliberately round,
 * obviously-fake numbers seeded for development — NOT verified DJP/BPJS rates) or {@code OFFICIAL}
 * (verified statutory figures). The column is {@code NOT NULL} with a CHECK constraint and the enum
 * has NO default, so a new rule row must declare its provenance explicitly and a placeholder can
 * never be silently treated as official. A {@code payroll_run} resolving ANY {@code
 * ILLUSTRATIVE_PLACEHOLDER} rule into its frozen set is itself flagged {@code
 * uses_illustrative_rules = true} and warns loudly.
 */
public enum RuleProvenance {
  ILLUSTRATIVE_PLACEHOLDER,
  OFFICIAL
}
