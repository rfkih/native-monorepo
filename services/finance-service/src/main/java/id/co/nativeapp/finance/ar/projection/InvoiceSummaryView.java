package id.co.nativeapp.finance.ar.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for one row of the invoice list — the invoice header joined to the customer name,
 * only the columns the list endpoint renders. {@code invoiceNumber} / {@code issueDate} / {@code
 * dueDate} are nullable (a DRAFT has none). Reached only from the service + repository layers.
 */
public interface InvoiceSummaryView {

  UUID getId();

  String getInvoiceNumber();

  UUID getCustomerId();

  String getCustomerName();

  String getStatus();

  LocalDate getIssueDate();

  LocalDate getDueDate();

  String getCurrency();

  long getTotalMinor();

  long getPaidMinor();
}
