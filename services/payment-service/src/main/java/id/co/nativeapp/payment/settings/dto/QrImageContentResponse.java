package id.co.nativeapp.payment.settings.dto;

/**
 * The static QRIS image blob as the serve endpoint returns it (the employee-service {@code
 * ReceiptContentResponse} idiom): the canonical content type, the sha256 (the ETag), and the bytes.
 * Mapped from the projection by the service layer — controllers never touch a projection
 * (CODE-STRUCTURE §3.3).
 */
public record QrImageContentResponse(String contentType, String sha256, byte[] data) {}
