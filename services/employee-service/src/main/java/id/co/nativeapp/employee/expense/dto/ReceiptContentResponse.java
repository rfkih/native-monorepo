package id.co.nativeapp.employee.expense.dto;

/**
 * The raw-bytes response for a receipt GET — the boundary translation for the serve endpoints
 * (CODE-STRUCTURE §4: no {@code @Entity} on the wire). Built by {@code ReceiptReader}, which
 * resolves the payload from whichever home it lives in (inline bytea on a legacy row, the object
 * store on an ADR-0048 row) — the controller only ever sees this finished shape. {@code sha256}
 * rides along as the serve ETag (the payment static-QR idiom).
 */
public record ReceiptContentResponse(String contentType, byte[] data, String sha256) {}
