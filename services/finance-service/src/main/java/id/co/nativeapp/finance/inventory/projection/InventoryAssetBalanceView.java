package id.co.nativeapp.finance.inventory.projection;

/**
 * Read projection over the aggregated {@code journal_line} balance for one GL account (the D4/D5
 * status read's {@code 1100 INVENTORY} balance) — only the columns the reader needs, never {@code
 * SELECT *}.
 *
 * <p>Backs the native read query on {@code InventoryAssetBalanceRepository#balanceFor}. Extracted
 * into the feature's dedicated {@code inventory.projection} package so the ArchUnit {@code
 * Projection} layer rule enforces it is accessed only from service and repository layers.
 */
public interface InventoryAssetBalanceView {

  long getTotalDebitMinor();

  long getTotalCreditMinor();

  String getCurrency();
}
