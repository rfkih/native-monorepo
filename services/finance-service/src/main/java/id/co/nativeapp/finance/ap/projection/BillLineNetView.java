package id.co.nativeapp.finance.ap.projection;

/**
 * Read projection for the ADR 0067 Phase B, §3 net-split: one bill line's extended total plus
 * whether it is inventory-flagged. {@code BillWriter} partitions an already-fetched, small per-bill
 * set of these in memory into {@code EXPENSE_NET} / {@code INVENTORY_NET} — not a new query shape
 * (the V54 migration note; {@code idx_bill_line_bill}, V27). Reached only from the service +
 * repository layers.
 */
public interface BillLineNetView {

  long getLineTotalMinor();

  boolean getIsInventory();
}
