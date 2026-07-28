package id.co.nativeapp.finance.ar.service;

/**
 * A service-layer input for one draft-invoice line: a description, a whole-unit quantity, and the
 * per-unit price in minor units. The line total is computed by the domain ({@code InvoiceLine.of})
 * in the invoice currency; finance never trusts a client-sent line total.
 *
 * @param description the line description (required, non-blank)
 * @param quantity the whole-unit quantity (&gt; 0)
 * @param unitPriceMinor the per-unit price in the invoice currency's minor units (&gt; 0)
 */
public record InvoiceLineInput(String description, int quantity, long unitPriceMinor) {}
