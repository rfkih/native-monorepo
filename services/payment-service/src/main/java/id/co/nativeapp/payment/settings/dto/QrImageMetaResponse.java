package id.co.nativeapp.payment.settings.dto;

/**
 * Metadata returned after a static-QRIS upload (the expense-receipt idiom): the canonical
 * (magic-byte-verified) content type, the stored byte count, and the sha256 the serve endpoint uses
 * as its ETag. The bytes themselves are only ever served by the authenticated image endpoint.
 */
public record QrImageMetaResponse(String contentType, int byteSize, String sha256) {}
