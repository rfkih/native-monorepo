package id.co.nativeapp.finance.ap.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for the bill detail header (bill joined to vendor name), including the
 * subtotal/tax breakdown and the illustrative flag. Lines and payments are separate projections
 * ({@link BillLineView}, {@link PaymentView}). Reached only from the service + repository layers.
 */
public interface BillDetailView {

  UUID getId();

  String getBillNumber();

  UUID getVendorId();

  String getVendorName();

  String getStatus();

  LocalDate getBillDate();

  LocalDate getDueDate();

  String getCurrency();

  long getSubtotalMinor();

  long getTaxMinor();

  long getTotalMinor();

  long getPaidMinor();

  boolean getUsesIllustrativeRules();
}
