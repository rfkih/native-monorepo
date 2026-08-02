package id.co.nativeapp.employee.expense.projection;

import java.util.UUID;

/**
 * A receipt's METADATA only (native-query read model, {@code
 * ExpenseReceiptRepository#findMetaByClaimId}) — id/content_type/byte_size/sha256, NEVER {@code
 * data} (CODE-STRUCTURE §3.3). Every list-shaped or existence-check read of a receipt goes through
 * this projection; the blob loads only via the inherited {@code findById} on the authenticated
 * serve path ({@code ReceiptReader}).
 */
public interface ReceiptMetaView {

  UUID getId();

  String getContentType();

  int getByteSize();

  String getSha256();
}
