package id.co.nativeapp.finance.ap.domain;

import java.util.UUID;

/** The bill already carries the maximum number of attachments (ADR 0084). */
public class BillAttachmentLimitExceededException extends RuntimeException {

  public BillAttachmentLimitExceededException(UUID billId, int max) {
    super("bill " + billId + " already has the maximum of " + max + " attachments");
  }
}
