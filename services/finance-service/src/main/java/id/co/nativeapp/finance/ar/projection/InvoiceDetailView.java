package id.co.nativeapp.finance.ar.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for the invoice detail header (invoice joined to customer name), including the
 * subtotal/tax breakdown and the illustrative flag. Lines and payments are separate projections
 * ({@link InvoiceLineView}, {@link PaymentView}). Reached only from the service + repository
 * layers.
 */
public interface InvoiceDetailView {

  UUID getId();

  String getInvoiceNumber();

  UUID getCustomerId();

  String getCustomerName();

  String getStatus();

  LocalDate getIssueDate();

  LocalDate getDueDate();

  String getCurrency();

  long getSubtotalMinor();

  long getTaxMinor();

  long getTotalMinor();

  long getPaidMinor();

  boolean getUsesIllustrativeRules();
}
