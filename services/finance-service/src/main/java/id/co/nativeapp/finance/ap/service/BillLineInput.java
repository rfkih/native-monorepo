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
 * @param inventory whether this line is an inventory purchase. Since ADR 0072 the flag routes the
 *     line's net under BOTH inventory methods: perpetual-active {@code Dr 2050 GRNI}, periodic
 *     (default) {@code Dr 5100 HPP} — a bill with NO inventory lines keeps the pre-0072 template
 *     path byte-identical. Defaults {@code false} at the API boundary (backward compatible).
 * @param ingredientId ADR 0072 P4 — the restaurant ingredient this line purchases (nullable;
 *     requires {@code inventory} and {@code ingredientQtyBase}); a posted bill carrying it rides
 *     {@code InventoryPurchaseRecorded} so restaurant receives the stock
 * @param ingredientName display-name snapshot (finance-side lists only)
 * @param ingredientQtyBase quantity purchased in the ingredient's BASE unit (&gt; 0 when present)
 */
public record BillLineInput(
    String description,
    int quantity,
    long unitPriceMinor,
    boolean inventory,
    java.util.UUID ingredientId,
    String ingredientName,
    Long ingredientQtyBase) {

  /** The pre-ADR-0072 shape — no ingredient linkage (existing call sites and tests unchanged). */
  public BillLineInput(String description, int quantity, long unitPriceMinor, boolean inventory) {
    this(description, quantity, unitPriceMinor, inventory, null, null, null);
  }
}
