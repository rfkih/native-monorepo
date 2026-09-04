package id.co.nativeapp.finance.companyexpense.domain;

/**
 * What a company expense buys — which decides the money leg's debit account (ADR 0072).
 *
 * <ul>
 *   <li>{@code GENERAL} — a category expense; the debit resolves from {@code gl_hint} via the
 *       versioned {@code mapping_rule} ({@code ""}→5000, {@code cogs}→5100, {@code supplies}→5200,
 *       {@code utilities}→5300).
 *   <li>{@code INVENTORY} — an ingredient purchase carrying lines; the debit is {@code
 *       AccountRole.COGS} (5100) under the periodic default, or {@code GRNI_CLEARING} (2050) when
 *       the company is perpetual-active, and the lines ride to restaurant-service as an {@code
 *       InventoryPurchaseRecorded} event to be received as stock.
 * </ul>
 */
public enum CompanyExpenseKind {
  GENERAL,
  INVENTORY
}
