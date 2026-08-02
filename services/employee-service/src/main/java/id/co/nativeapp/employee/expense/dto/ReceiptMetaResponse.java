package id.co.nativeapp.employee.expense.dto;

import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import java.util.UUID;

/** The metadata response for an upload — never the raw bytes (those stream from the GET). */
public record ReceiptMetaResponse(
    UUID id, UUID claimId, String contentType, int byteSize, String sha256) {

  public static ReceiptMetaResponse from(ExpenseReceipt receipt) {
    return new ReceiptMetaResponse(
        receipt.getId(),
        receipt.getClaimId(),
        receipt.getContentType(),
        receipt.getByteSize(),
        receipt.getSha256());
  }
}
