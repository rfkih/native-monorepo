package id.co.nativeapp.restaurant.bill.dto;

/**
 * A bill attachment's servable content (ADR 0063): the canonical content type, the payload bytes
 * (fetched from the object store), and the sha256 (served as the ETag). Internal carrier from the
 * reader to the controller's streaming response — not a JSON body.
 */
public record BillAttachmentContentResponse(String contentType, byte[] data, String sha256) {}
