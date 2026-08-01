package id.co.nativeapp.finance.gl.projection;

import java.util.UUID;

/**
 * Read projection for the sale-aggregate lookup: the minimum columns from {@code journal_entry}
 * that the reversal writer needs to find the original SALE entry by its {@code sale_aggregate_id}
 * (the sale UUID from restaurant-service, stored by {@code RevenuePostingWriter} at posting time —
 * V18).
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.finance.gl.repository.JournalEntryRepository#findBySaleAggregateId}. Used by
 * {@code ReversalPostingWriter} to look up the original SALE entry's id (from which the lines are
 * fetched) for per-leg void/refund unwind (Phase 2). Finance NEVER recomputes amounts — it reads
 * each original line's debit/credit and negates them. Extracted into {@code gl.projection} so the
 * ArchUnit {@code Projection} layer rule enforces access only from service + repository layers.
 */
public interface JournalEntrySaleView {

  /** The journal_entry primary key — used to fetch the lines for per-leg negation. */
  UUID getId();

  /** ISO-4217 currency code for the entry — the reversal entry must use the same currency. */
  String getCurrency();

  /**
   * Whether the original SALE entry was produced from an illustrative posting rule. Propagated onto
   * the reversal entry so the void/refund is also flagged illustrative when the original was.
   */
  boolean getUsesIllustrativeRules();

  /**
   * The precomputed net revenue in minor units (subtotal − discount) stored by {@code
   * RevenuePostingWriter} at posting time (Phase 2 — V19). Used by the reversal writer to unwind
   * the consolidated_revenue and consolidated_pnl read models by exactly the same amount that was
   * accumulated. Returns {@code null} for SALE entries predating V19 (legacy); the reversal writer
   * falls back to the grand total (net == gross for legacy Phase 1 sales).
   */
  Long getNetRevenueMinor();

  /**
   * The precomputed grand total in minor units (what the customer owed = the tender legs) stored by
   * {@code RevenuePostingWriter} at posting time (V38). The reversal writer negates this for the
   * void contra and compares against it for the refund full-vs-partial gate — a STORED value rather
   * than reconstructing it from the GL lines (which depends on the mutable role_account_map).
   * Returns {@code null} for SALE entries predating V38; the reversal writer falls back to line
   * reconstruction.
   */
  Long getGrandTotalMinor();
}
