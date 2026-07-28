package id.co.nativeapp.finance.ar.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for one outstanding invoice used to build the AR aging report — the customer, the
 * due date, and the outstanding amount ({@code total − paid}). The {@code AgingReader} groups these
 * by customer and buckets them by days-overdue vs an {@code asOf} date in Java (avoiding SQL date
 * arithmetic in the projection). Reached only from the service + repository layers.
 */
public interface AgingInvoiceView {

  UUID getCustomerId();

  String getCustomerName();

  String getCurrency();

  LocalDate getDueDate();

  long getOutstandingMinor();
}
