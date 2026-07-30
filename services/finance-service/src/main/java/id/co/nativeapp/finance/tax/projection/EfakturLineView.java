package id.co.nativeapp.finance.tax.projection;

import java.time.LocalDate;

/**
 * Read projection over one output tax invoice for the e-Faktur export (Phase 4 Tax / PPN) — the AR
 * invoices issued in a period that carry output VAT. Backs the native read query on {@code
 * TaxFilingRepository#findEfakturLines} (a native join over {@code invoice} + {@code customer} — a
 * SQL table reference, not a Java dependency on the {@code ar} feature).
 *
 * <p>DPP (<em>Dasar Pengenaan Pajak</em>) is the tax base = the invoice subtotal; PPN is the output
 * VAT. NOTE: this is an ILLUSTRATIVE flat layout, NOT the certified DJP e-Faktur import schema (no
 * NSFP tax-invoice serial numbers, no certificate) — ADR 0017 SME gate.
 */
public interface EfakturLineView {

  String getInvoiceNumber();

  LocalDate getIssueDate();

  String getCustomerName();

  /** The customer's NPWP / tax id; nullable. */
  String getCustomerTaxId();

  /** DPP — the tax base (invoice subtotal), in minor units. */
  long getDppMinor();

  /** PPN — the output VAT, in minor units. */
  long getPpnMinor();

  long getTotalMinor();

  String getCurrency();
}
