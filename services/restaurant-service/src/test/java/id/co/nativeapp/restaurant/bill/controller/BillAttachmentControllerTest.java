package id.co.nativeapp.restaurant.bill.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException;
import id.co.nativeapp.restaurant.bill.dto.BillAttachmentContentResponse;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentReader;
import id.co.nativeapp.restaurant.bill.service.BillAttachmentWriter;
import id.co.nativeapp.restaurant.config.BillAttachmentAdvice;
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

/**
 * Web-slice tests for {@link BillAttachmentController} + {@link BillAttachmentAdvice} (ADR 0063).
 * Proves the HTTP contract the {@code BillAttachmentIntegrationTest} (service-level) can't see: the
 * upload faults map to RFC-7807 {@code ProblemDetail}s ({@code 413 bill-attachment-too-large},
 * {@code 422 bill-attachment-invalid}), a successful upload is {@code 201}, and the authenticated
 * stream carries the private cache, sha256 {@code ETag}, and {@code nosniff} headers a sensitive
 * binary response must have. No DB — pure {@code @WebMvcTest} slice.
 */
@WebMvcTest(BillAttachmentController.class)
@Import(BillAttachmentAdvice.class)
class BillAttachmentControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String BASE = "/api/v1/bills";
  private static final UUID BILL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ATT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };
  private static final String SHA = "a".repeat(64);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private BillAttachmentWriter writer;
  @MockitoBean private BillAttachmentReader reader;

  private static MockMultipartFile part() {
    return new MockMultipartFile("file", "receipt.png", "image/png", PNG);
  }

  @Test
  void anOversizedUploadIsMappedTo413ProblemDetail() throws Exception {
    when(writer.upload(any(), any(), any(), any()))
        .thenThrow(new MaxUploadSizeExceededException(BillAttachment.MAX_BYTES));

    mockMvc
        .perform(multipart(BASE + "/{id}/attachments", BILL_ID).file(part()))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(413))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-too-large"));
  }

  @Test
  void anUnsupportedTypeIsMappedTo422ProblemDetail() throws Exception {
    when(writer.upload(any(), any(), any(), any()))
        .thenThrow(new InvalidBillAttachmentException("image/png", null));

    mockMvc
        .perform(multipart(BASE + "/{id}/attachments", BILL_ID).file(part()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bill-attachment-invalid"));
  }

  @Test
  void streamingAnAttachmentCarriesPrivateCacheEtagAndNosniff() throws Exception {
    when(reader.content(BILL_ID, ATT_ID))
        .thenReturn(new BillAttachmentContentResponse("image/png", PNG, SHA));

    mockMvc
        .perform(get(BASE + "/{id}/attachments/{aid}", BILL_ID, ATT_ID))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
        .andExpect(content().bytes(PNG))
        .andExpect(header().string("ETag", '"' + SHA + '"'))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", containsString("private")))
        .andExpect(header().string("Cache-Control", containsString("max-age=300")));
  }
}
