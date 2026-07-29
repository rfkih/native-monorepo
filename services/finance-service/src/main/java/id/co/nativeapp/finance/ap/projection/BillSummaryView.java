package id.co.nativeapp.finance.ap.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for one row of the bill list — the bill header joined to the vendor name, only
 * the columns the list endpoint renders. {@code billNumber} / {@code billDate} / {@code dueDate}
 * are nullable (a DRAFT has none). Reached only from the service + repository layers.
 */
public interface BillSummaryView {

  UUID getId();

  String getBillNumber();

  UUID getVendorId();

  String getVendorName();

  String getStatus();

  LocalDate getBillDate();

  LocalDate getDueDate();

  String getCurrency();

  long getTotalMinor();

  long getPaidMinor();
}
