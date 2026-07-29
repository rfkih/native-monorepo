package id.co.nativeapp.finance.ap.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for one outstanding bill used to build the AP aging report — the vendor, the due
 * date, and the outstanding amount ({@code total − paid}). The {@code ApAgingReader} groups these
 * by vendor and buckets them by days-overdue vs an {@code asOf} date in Java (avoiding SQL date
 * arithmetic in the projection). Reached only from the service + repository layers.
 */
public interface AgingBillView {

  UUID getVendorId();

  String getVendorName();

  String getCurrency();

  LocalDate getDueDate();

  long getOutstandingMinor();
}
