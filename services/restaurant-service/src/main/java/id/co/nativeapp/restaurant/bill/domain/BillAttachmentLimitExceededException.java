package id.co.nativeapp.restaurant.bill.domain;

import java.util.UUID;

/**
 * A bill already carries the maximum number of attachments (flaw-audit W1: the cap bounds both
 * per-tenant object-storage growth and the serve-path fan-out — without it a looping client writes
 * unbounded 5 MiB objects and a single bill view fires unbounded parallel thumbnail fetches).
 * Mapped to {@code 422} ({@code bill-attachment-limit}) by {@code config.BillAttachmentAdvice}.
 */
public class BillAttachmentLimitExceededException extends RuntimeException {

  public BillAttachmentLimitExceededException(UUID billId, int max) {
    super("bill " + billId + " already has the maximum of " + max + " attachments");
  }
}
