package id.co.nativeapp.finance.ar.projection;

/**
 * Read projection for one {@code invoice_line} row of an invoice detail. Reached only from the
 * service + repository layers.
 */
public interface InvoiceLineView {

  int getLineNo();

  String getDescription();

  int getQuantity();

  long getUnitPriceMinor();

  long getLineTotalMinor();
}
