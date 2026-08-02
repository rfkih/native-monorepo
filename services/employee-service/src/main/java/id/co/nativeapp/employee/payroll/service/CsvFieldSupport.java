package id.co.nativeapp.employee.payroll.service;

/**
 * Shared CSV cell escaping for every payroll CSV export ({@link BankFileReader}, {@link
 * PayrollReportReader}) — a plain static utility, not a Spring bean (no state, no injection
 * needed).
 *
 * <p><strong>Formula-injection guard (the ADR 0032 §S2/S3 review lesson).</strong> A CSV cell
 * beginning with {@code =}, {@code +}, {@code -}, or {@code @} can be interpreted as a formula by a
 * spreadsheet application that opens the file (a well-known CSV-export hazard) — e.g. an
 * employee/vendor-controlled free-text field (a name, a merchant string) crafted as {@code
 * "=cmd|'/c calc'!A0"}. Every field is checked BEFORE the RFC-4180 quoting below and, if it starts
 * with one of those characters, prefixed with a single quote {@code '} so a spreadsheet reads it
 * back as literal text (the standard Excel/Google Sheets convention) rather than evaluating it.
 *
 * <p>This closes the residual {@code BankFileReader} left open at Track P phase P5 (ADR 0032: "the
 * bank-file CSV does not defend against formula injection... tracked as a follow-up") — both
 * readers now share the ONE escaping implementation so the guard can never apply to one export and
 * not the other.
 */
// public (not package-private) solely so a dedicated unit test can exercise every escaping case
// directly without threading malicious input through a full HTTP/service pipeline — it is still
// never registered as a Spring bean and carries no state.
public final class CsvFieldSupport {

  /** The four RFC-recognized formula-trigger characters (Excel/Sheets convention). */
  private static final String FORMULA_TRIGGER_CHARS = "=+-@";

  private CsvFieldSupport() {}

  /**
   * Escapes one CSV field: the formula-injection guard first, then RFC-4180-ish quoting (a field
   * containing a comma/quote/newline is wrapped in double quotes, with embedded quotes doubled).
   * {@code null} becomes an empty cell.
   */
  public static String field(String value) {
    if (value == null) {
      return "";
    }
    String escaped = value;
    if (!escaped.isEmpty() && FORMULA_TRIGGER_CHARS.indexOf(escaped.charAt(0)) >= 0) {
      escaped = "'" + escaped;
    }
    if (escaped.contains(",")
        || escaped.contains("\"")
        || escaped.contains("\n")
        || escaped.contains("\r")) {
      return "\"" + escaped.replace("\"", "\"\"") + "\"";
    }
    return escaped;
  }
}
