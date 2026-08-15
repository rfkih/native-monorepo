package id.co.nativeapp.restaurant.bill.controller;

import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentContentResponse;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentReader;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bill attachments (ADR 0063) — photos/PDF of the "real receipt" or a supporting document for a
 * bill/tagihan. Sub-resource of {@code /api/v1/bills} (a separate controller so {@link
 * id.co.nativeapp.restaurant.bill.controller.BillController} stays focused); the gateway {@code
 * /api/v1/bills/**} POS_ROLES route already covers these — no gateway change.
 *
 * <p>The bytes are stored in MinIO (ADR 0048) but served ONLY through the authenticated stream
 * endpoint below (private cache + sha256 ETag), NOT the anonymous {@code /api/media} path — a
 * bill's receipt is business-sensitive.
 */
@Tag(name = "Bill attachments", description = "Photos/PDF documents attached to a bill (tagihan)")
@RestController
@RequestMapping("/api/v1/bills")
public class BillAttachmentController {

  private final BillAttachmentWriter writer;
  private final BillAttachmentReader reader;

  public BillAttachmentController(BillAttachmentWriter writer, BillAttachmentReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  @Operation(summary = "Attach a photo/PDF (real receipt or document) to a bill")
  @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<BillAttachmentMetaResponse> upload(
      @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
    BillAttachment attachment =
        writer.upload(id, file.getContentType(), file.getBytes(), file.getOriginalFilename());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BillAttachmentMetaResponse.from(attachment));
  }

  @Operation(summary = "List a bill's attachments (metadata only)")
  @GetMapping("/{id}/attachments")
  public ResponseEntity<List<BillAttachmentMetaResponse>> list(@PathVariable UUID id) {
    return ResponseEntity.ok(reader.list(id));
  }

  @Operation(summary = "Stream one of a bill's attachments (authenticated)")
  @GetMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<byte[]> content(@PathVariable UUID id, @PathVariable UUID attachmentId) {
    BillAttachmentContentResponse content = reader.content(id, attachmentId);
    // Private short cache + the content sha256 as ETag (immutable content → a revisit revalidates
    // cheaply to 304), nosniff on an authenticated binary response. Same idiom as the
    // expense-receipt
    // serve — never the anonymous immutable /api/media caching (this is sensitive).
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.data().length)
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePrivate())
        .eTag('"' + content.sha256() + '"')
        .header("X-Content-Type-Options", "nosniff")
        .body(content.data());
  }

  @Operation(summary = "Remove one of a bill's attachments")
  @DeleteMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID attachmentId) {
    writer.delete(id, attachmentId);
    return ResponseEntity.noContent().build();
  }
}
