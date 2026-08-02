package id.co.nativeapp.employee.expense.dto;

import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;

/**
 * The raw-bytes response for a receipt GET — the boundary translation for the serve endpoints
 * (CODE-STRUCTURE §4: no {@code @Entity} on the wire). Extracting the content type/bytes HERE
 * (inside the DTO's own static factory, called with the entity as an argument) rather than in the
 * controller keeps the controller from ever invoking an {@link ExpenseReceipt} accessor directly —
 * the {@code controllersMustNotDependOnEntities} ArchUnit rule flags a controller calling an entity
 * GETTER, even though passing the entity itself as a constructor/factory argument (the {@code
 * ExpenseClaimResponse.from(claim)} idiom) is fine.
 */
public record ReceiptContentResponse(String contentType, byte[] data) {

  public static ReceiptContentResponse from(ExpenseReceipt receipt) {
    return new ReceiptContentResponse(receipt.getContentType(), receipt.getData());
  }
}
