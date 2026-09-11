package id.co.nativeapp.finance.ap.domain;

import java.util.UUID;

/**
 * The vendor's invoice number is already on a live (non-void) bill of the same vendor in this
 * tenant (ADR 0084) — the one refusal the "Tagihan baru" form exists to make. Mapped to 409 {@code
 * bill-duplicate-invoice}.
 */
public class DuplicateVendorInvoiceException extends RuntimeException {

  private final UUID vendorId;
  private final String vendorInvoiceNumber;

  public DuplicateVendorInvoiceException(UUID vendorId, String vendorInvoiceNumber) {
    super(
        "invoice number '"
            + vendorInvoiceNumber
            + "' is already recorded for vendor "
            + vendorId
            + " on a live bill");
    this.vendorId = vendorId;
    this.vendorInvoiceNumber = vendorInvoiceNumber;
  }

  public UUID getVendorId() {
    return vendorId;
  }

  public String getVendorInvoiceNumber() {
    return vendorInvoiceNumber;
  }
}
