package id.co.nativeapp.finance.ap.service;

/**
 * A service-layer input for one draft-bill line: a description, a whole-unit quantity, the per-unit
 * price in minor units, and whether the line routes to inventory (ADR 0067 Phase B, §3). The line
 * total is computed by the domain ({@code BillLine.of}) in the bill currency; finance never trusts
 * a client-sent line total.
 *
 * @param description the line description (required, non-blank)
 * @param quantity the whole-unit quantity (&gt; 0)
 * @param unitPriceMinor the per-unit price in the bill currency's minor units (&gt; 0)
 * @param inventory whether this line is an inventory purchase. Read ONLY when the owning company is
 *     perpetual-active (steers {@code Dr 2050 GRNI} instead of {@code Dr 5000 EXPENSE}); ignored
 *     (ALWAYS routes to {@code EXPENSE}) for a non-activated tenant — every tenant in Phase B.
 *     Defaults {@code false} at the API boundary (backward-compatible: an old client that omits the
 *     field behaves exactly as before).
 */
public record BillLineInput(
    String description, int quantity, long unitPriceMinor, boolean inventory) {}
