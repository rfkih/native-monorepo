package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.ap.controller.ApAdvice;
import id.co.nativeapp.finance.ap.controller.BillAttachmentController;
import id.co.nativeapp.finance.ap.domain.BillAttachmentLimitExceededException;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.InvalidBillAttachmentException;
import id.co.nativeapp.finance.ap.dto.BillAttachmentContentMeta;
import id.co.nativeapp.finance.ap.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.finance.ap.service.BillAttachmentReader;
import id.co.nativeapp.finance.ap.service.BillAttachmentWriter;
import id.co.nativeapp.finance.config.BillAttachmentAdvice;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Web-slice tests for {@code /api/v1/ap/bills/{id}/attachments} (ADR 0084) — no DB, no store. */
@WebMvcTest(BillAttachmentController.class)
@Import({ApAdvice.class, BillAttachmentAdvice.class, ApiExceptionHandler.class})
class BillAttachmentControllerTest {

  private static final UUID BILL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID ATTACHMENT = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
  private static final String SHA = "ab".repeat(32);

  @Autowired private MockMvc mockMvc;
  @MockitoBean private BillAttachmentWriter writer;
  @MockitoBean private BillAttachmentReader reader;

  private static BillAttachmentMetaResponse meta() {
    return new BillAttachmentMetaResponse(
        ATTACHMENT,
        "application/pdf",
        1234L,
        SHA,
        "faktur.pdf",
        Instant.parse("2026-09-11T03:00:00Z"));
  }

  @Test
  void uploadReturns201WithLocationAndTheMeta() throws Exception {
    when(writer.upload(eq(BILL), any(), any(), eq("faktur.pdf"))).thenReturn(meta());
    MockMultipartFile file =
        new MockMultipartFile("file", "faktur.pdf", "application/pdf", "%PDF-1.4 x".getBytes());

    mockMvc
        .perform(multipart("/api/v1/ap/bills/" + BILL + "/attachments").file(file))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", "/api/v1/ap/bills/" + BILL + "/attachments/" + ATTACHMENT))
        .andExpect(jsonPath("$.contentType").value("application/pdf"))
        .andExpect(jsonPath("$.originalFilename").value("faktur.pdf"));
  }

  @Test
  void anUnsupportedFileIsA422AndTooLargeIsA413() throws Exception {
    when(writer.upload(eq(BILL), any(), any(), any()))
        .thenThrow(new InvalidBillAttachmentException("text/plain", null));
    MockMultipartFile text =
        new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());
    mockMvc
        .perform(multipart("/api/v1/ap/bills/" + BILL + "/attachments").file(text))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-invalid"))
        .andExpect(jsonPath("$.detail").value("Only JPEG, PNG, WEBP or PDF are accepted."));

    when(writer.upload(eq(BILL), any(), any(), any()))
        .thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024));
    mockMvc
        .perform(multipart("/api/v1/ap/bills/" + BILL + "/attachments").file(text))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-too-large"));

    when(writer.upload(eq(BILL), any(), any(), any()))
        .thenThrow(new BillAttachmentLimitExceededException(BILL, 10));
    mockMvc
        .perform(multipart("/api/v1/ap/bills/" + BILL + "/attachments").file(text))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-limit"));
  }

  @Test
  void listReturnsTheMeta() throws Exception {
    when(reader.list(BILL)).thenReturn(List.of(meta()));
    mockMvc
        .perform(get("/api/v1/ap/bills/" + BILL + "/attachments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(ATTACHMENT.toString()))
        .andExpect(jsonPath("$[0].sha256").value(SHA));
  }

  @Test
  void contentIsServedPrivatelyWithAnEtagAndRevalidatesTo304() throws Exception {
    when(reader.contentMeta(BILL, ATTACHMENT))
        .thenReturn(
            new BillAttachmentContentMeta(
                "application/pdf", SHA, "finance/t/bill/x.pdf", "faktur \"gas\"; ü.pdf"));
    when(reader.payload("finance/t/bill/x.pdf")).thenReturn("%PDF-1.4 x".getBytes());

    mockMvc
        .perform(get("/api/v1/ap/bills/" + BILL + "/attachments/" + ATTACHMENT))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(header().string("ETag", "\"" + SHA + "\""))
        .andExpect(header().string("Cache-Control", "max-age=300, private"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Disposition", "inline; filename=\"faktur gas .pdf\""));

    mockMvc
        .perform(
            get("/api/v1/ap/bills/" + BILL + "/attachments/" + ATTACHMENT)
                .header("If-None-Match", "\"" + SHA + "\""))
        .andExpect(status().isNotModified());
  }

  @Test
  void anUnknownAttachmentIsA404AndDeleteIs204() throws Exception {
    when(reader.contentMeta(BILL, ATTACHMENT)).thenThrow(new BillNotFoundException(BILL));
    mockMvc
        .perform(get("/api/v1/ap/bills/" + BILL + "/attachments/" + ATTACHMENT))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ap-not-found"));

    mockMvc
        .perform(delete("/api/v1/ap/bills/" + BILL + "/attachments/" + ATTACHMENT))
        .andExpect(status().isNoContent());
  }

  @Test
  void aMultipartWithoutTheFilePartIsA400NotA500() throws Exception {
    mockMvc
        .perform(multipart("/api/v1/ap/bills/" + BILL + "/attachments"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-malformed"));
  }
}
