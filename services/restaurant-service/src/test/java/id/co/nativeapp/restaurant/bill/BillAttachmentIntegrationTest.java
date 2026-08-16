package id.co.nativeapp.restaurant.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentContentMeta;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentReader;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentWriter;
import id.co.nativeapp.restaurant.bill.service.BillService;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Testcontainers proof (real Postgres + the in-memory {@code MediaStorage}) of bill attachments
 * (ADR 0063): upload a photo AND a PDF, list, stream the exact bytes back, content-addressed dedup,
 * the type/size guards, and the tenant/bill scoping (an attachment can only be read under its own
 * bill).
 */
@SpringBootTest
class BillAttachmentIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "aa630001-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TENANT_B = "bb630001-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String ACTOR = "cashier@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("cc630001-cccc-cccc-cccc-cccccccccccc");

  // Minimal payloads with valid leading magic bytes (the validator only reads the signature).
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };
  private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\n".getBytes(StandardCharsets.US_ASCII);

  @Autowired private BillService billService;
  @Autowired private BillAttachmentWriter writer;
  @Autowired private BillAttachmentReader reader;
  @Autowired private MediaStorage mediaStorage;

  private static <T> T asA(Callable<T> action) throws Exception {
    return TenantContext.callAs(TENANT_A, ACTOR, action);
  }

  private UUID openBill() throws Exception {
    return asA(() -> billService.open(new OpenBillRequest(BUSINESS, null, "Guest"))).id();
  }

  @Test
  void uploadsPhotoAndPdfListsThemAndStreamsTheExactBytesBack() throws Exception {
    UUID billId = openBill();

    BillAttachment photo = asA(() -> writer.upload(billId, "image/png", PNG, "receipt.png"));
    BillAttachment doc = asA(() -> writer.upload(billId, "application/pdf", PDF, "invoice.pdf"));

    assertThat(photo.getContentType()).isEqualTo("image/png");
    assertThat(photo.getByteSize()).isEqualTo(PNG.length);
    assertThat(photo.getSha256()).isEqualTo(Sha256.hex(PNG));
    assertThat(photo.getOriginalFilename()).isEqualTo("receipt.png");
    assertThat(doc.getContentType()).isEqualTo("application/pdf");
    assertThat(doc.getSha256()).isEqualTo(Sha256.hex(PDF));

    List<BillAttachmentMetaResponse> list = asA(() -> reader.list(billId));
    assertThat(list).hasSize(2);
    assertThat(list)
        .extracting(BillAttachmentMetaResponse::contentType)
        .containsExactlyInAnyOrder("image/png", "application/pdf");

    // The serve is TWO proxied calls (flaw-audit C1): a transactional metadata read, then the
    // byte fetch with NO transaction — composed here exactly as the controller composes them.
    BillAttachmentContentMeta meta = asA(() -> reader.contentMeta(billId, photo.getId()));
    assertThat(meta.contentType()).isEqualTo("image/png");
    assertThat(meta.sha256()).isEqualTo(Sha256.hex(PNG));
    assertThat(reader.payload(meta.objectKey())).isEqualTo(PNG);
  }

  @Test
  void reUploadingTheSameBytesOnTheSameBillIsIdempotent() throws Exception {
    UUID billId = openBill();
    BillAttachment first = asA(() -> writer.upload(billId, "image/png", PNG, "a.png"));
    // Flaw-audit W3: a byte-identical re-upload (network retry / double-tap) returns the EXISTING
    // row — one row, one object, no duplicate.
    BillAttachment second = asA(() -> writer.upload(billId, "image/png", PNG, "b.png"));
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(asA(() -> reader.list(billId))).hasSize(1);
  }

  @Test
  void anEleventhAttachmentOnOneBillIsRejected() throws Exception {
    UUID billId = openBill();
    // Ten DISTINCT payloads fill the cap (vary a trailing byte — the magic bytes stay valid).
    for (int i = 0; i < BillAttachmentWriter.MAX_ATTACHMENTS_PER_BILL; i++) {
      byte[] distinct = PNG.clone();
      distinct[distinct.length - 1] = (byte) i;
      asA(() -> writer.upload(billId, "image/png", distinct, "p" + ".png"));
    }
    byte[] eleventh = PNG.clone();
    eleventh[eleventh.length - 1] = (byte) 0x7F;
    assertThatThrownBy(() -> asA(() -> writer.upload(billId, "image/png", eleventh, "x.png")))
        .isInstanceOf(BillAttachmentLimitExceededException.class);
    // A byte-identical re-upload of an EXISTING attachment still replays fine at the cap.
    byte[] replay = PNG.clone();
    replay[replay.length - 1] = (byte) 3;
    assertThat(asA(() -> writer.upload(billId, "image/png", replay, "p.png")).getId()).isNotNull();
    assertThat(asA(() -> reader.list(billId)))
        .hasSize(BillAttachmentWriter.MAX_ATTACHMENTS_PER_BILL);
  }

  @Test
  void rejectsAnUnsupportedTypeAndAnOversizedPayload() throws Exception {
    UUID billId = openBill();

    assertThatThrownBy(
            () -> asA(() -> writer.upload(billId, "image/png", new byte[] {1, 2, 3, 4}, "x")))
        .isInstanceOf(InvalidBillAttachmentException.class);

    byte[] tooBig = new byte[BillAttachment.MAX_BYTES + 1];
    assertThatThrownBy(() -> asA(() -> writer.upload(billId, "application/pdf", tooBig, "big.pdf")))
        .isInstanceOf(MaxUploadSizeExceededException.class);
  }

  @Test
  void anAttachmentCanOnlyBeReadUnderItsOwnBill() throws Exception {
    UUID billA = openBill();
    UUID billB = openBill();
    BillAttachment onA = asA(() -> writer.upload(billA, "image/png", PNG, "a.png"));

    // Same tenant, WRONG bill → 404 (never serve bill A's attachment under bill B).
    assertThatThrownBy(() -> asA(() -> reader.contentMeta(billB, onA.getId())))
        .isInstanceOf(BillNotFoundException.class);
  }

  @Test
  void uploadToAnUnknownBillIs404() {
    assertThatThrownBy(() -> asA(() -> writer.upload(UUID.randomUUID(), "image/png", PNG, "x.png")))
        .isInstanceOf(BillNotFoundException.class);
  }

  @Test
  void anotherTenantCannotSeeABillsAttachments() throws Exception {
    UUID billId = openBill();
    asA(() -> writer.upload(billId, "image/png", PNG, "a.png"));

    // RLS scopes the list to the bound tenant — tenant B sees none of tenant A's attachments.
    List<BillAttachmentMetaResponse> asB =
        TenantContext.callAs(TENANT_B, ACTOR, () -> reader.list(billId));
    assertThat(asB).isEmpty();
  }

  @Test
  void anEmptyPayloadIsRejectedAsAnUnsupportedTypeNotAsTooLarge() throws Exception {
    UUID billId = openBill();
    // A zero-byte part is not "too large" (413) — it has no magic bytes, so it is an unsupported
    // type (422). Guards the S1 fix in BillAttachmentWriter.
    assertThatThrownBy(
            () -> asA(() -> writer.upload(billId, "image/png", new byte[0], "empty.png")))
        .isInstanceOf(InvalidBillAttachmentException.class);
  }

  @Test
  void deleteRemovesTheRowButKeepsTheContentAddressedObject() throws Exception {
    UUID billId = openBill();
    BillAttachment onA = asA(() -> writer.upload(billId, "image/png", PNG, "a.png"));

    asA(
        () -> {
          writer.delete(billId, onA.getId());
          return null;
        });

    // The metadata row is gone…
    assertThat(asA(() -> reader.list(billId))).isEmpty();
    // …but the shared, content-addressed object is deliberately NOT deleted (ADR 0048): a
    // byte-identical attachment on a sibling bill would still reference it.
    assertThat(mediaStorage.get(onA.getObjectKey()).data()).isEqualTo(PNG);
  }

  @Test
  void deleteUnderTheWrongBillIs404AndDoesNotRemoveTheRow() throws Exception {
    UUID billA = openBill();
    UUID billB = openBill();
    BillAttachment onA = asA(() -> writer.upload(billA, "image/png", PNG, "a.png"));

    // Same tenant, WRONG bill → 404: an attachment can only be deleted under its own bill (IDOR).
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      writer.delete(billB, onA.getId());
                      return null;
                    }))
        .isInstanceOf(BillNotFoundException.class);
    assertThat(asA(() -> reader.list(billA))).hasSize(1);
  }

  @Test
  void anotherTenantCannotDeleteABillsAttachment() throws Exception {
    UUID billId = openBill();
    BillAttachment onA = asA(() -> writer.upload(billId, "image/png", PNG, "a.png"));

    // RLS scopes findById to the bound tenant — tenant B's delete never finds tenant A's row (404).
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B,
                    ACTOR,
                    () -> {
                      writer.delete(billId, onA.getId());
                      return null;
                    }))
        .isInstanceOf(BillNotFoundException.class);
    assertThat(asA(() -> reader.list(billId))).hasSize(1);
  }
}
