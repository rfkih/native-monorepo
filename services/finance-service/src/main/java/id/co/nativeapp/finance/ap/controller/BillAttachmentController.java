package id.co.nativeapp.finance.ap.controller;

import id.co.nativeapp.finance.ap.dto.BillAttachmentContentMeta;
import id.co.nativeapp.finance.ap.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.finance.ap.service.BillAttachmentReader;
import id.co.nativeapp.finance.ap.service.BillAttachmentWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * The vendor's invoice as evidence on an AP bill (ADR 0084): upload, list, serve (authenticated,
 * private cache, ETag) and remove. Under the gateway's {@code /api/v1/ap/**} FINANCE_ROLES rule.
 */
@Tag(name = "AP bill attachments", description = "Photos/PDFs of the vendor's invoice on a bill")
@RestController
@RequestMapping("/api/v1/ap/bills")
public class BillAttachmentController {

  private final BillAttachmentWriter writer;
  private final BillAttachmentReader reader;

  public BillAttachmentController(BillAttachmentWriter writer, BillAttachmentReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  @Operation(summary = "Attach a photo/PDF of the vendor's invoice to a bill")
  @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<BillAttachmentMetaResponse> upload(
      @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
    BillAttachmentMetaResponse attachment =
        writer.upload(id, file.getContentType(), file.getBytes(), file.getOriginalFilename());
    // 201 + Location. A byte-identical re-upload returns the EXISTING row idempotently.
    return ResponseEntity.created(
            URI.create("/api/v1/ap/bills/" + id + "/attachments/" + attachment.id()))
        .body(attachment);
  }

  @Operation(summary = "List a bill's attachments (metadata only)")
  @GetMapping("/{id}/attachments")
  public ResponseEntity<List<BillAttachmentMetaResponse>> list(@PathVariable UUID id) {
    return ResponseEntity.ok(reader.list(id));
  }

  @Operation(summary = "Stream one of a bill's attachments (authenticated)")
  @GetMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<byte[]> content(
      @PathVariable UUID id, @PathVariable UUID attachmentId, WebRequest request) {
    // A short transactional METADATA read authorizes the serve and yields the ETag; the object
    // store fetch runs OUTSIDE any DB transaction. An If-None-Match revalidation is answered 304
    // from the metadata row alone.
    BillAttachmentContentMeta meta = reader.contentMeta(id, attachmentId);
    String etag = meta.etag();
    if (request.checkNotModified(etag)) {
      return null;
    }
    byte[] data = reader.payload(meta.objectKey());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(meta.contentType()))
        .contentLength(data.length)
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
        .eTag(etag)
        .header("X-Content-Type-Options", "nosniff")
        // Inline with a sanitised ASCII name: the viewer decides, the header never carries a
        // client string verbatim.
        .header("Content-Disposition", "inline; filename=\"" + meta.asciiFilename() + "\"")
        .body(data);
  }

  @Operation(summary = "Remove one of a bill's attachments")
  @DeleteMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID attachmentId) {
    writer.delete(id, attachmentId);
    return ResponseEntity.noContent().build();
  }
}
