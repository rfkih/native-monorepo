package id.co.nativeapp.finance.inventory.dto;

import java.time.Instant;

/**
 * The bound company's perpetual-inventory election status (ADR 0067 §5, Phase D4/D5) — returned by
 * both {@code POST /api/v1/inventory-method/activate} (on success) and {@code GET
 * /api/v1/inventory-method} (the read).
 *
 * <p>{@code inventoryAssetMinor} is the CURRENT GL balance of account {@code 1100} for this company
 * (Σdebit − Σcredit over every {@code journal_line} posted to it, regardless of whether perpetual
 * inventory is active — ADR 0037 opening balances can already post a {@code 1100} line before any
 * activation). {@code inventoryAssetNegative} is the D5 monitor signal the console surfaces
 * (({@code inventoryAssetMinor < 0}) — the exact failure ADR 0067 exists to prevent going forward).
 * {@code currency} is the election's own currency when active, else the currency of whatever has
 * posted to {@code 1100} so far, else {@code null} (nothing to report yet).
 */
public record InventoryMethodStatusResponse(
    boolean active,
    String method,
    String cutoverPeriod,
    Instant activatedAt,
    long inventoryAssetMinor,
    boolean inventoryAssetNegative,
    String currency) {}
