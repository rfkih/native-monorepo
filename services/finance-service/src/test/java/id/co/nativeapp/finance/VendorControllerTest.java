package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.ap.controller.ApAdvice;
import id.co.nativeapp.finance.ap.controller.VendorController;
import id.co.nativeapp.finance.ap.domain.VendorNotFoundException;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.VendorReader;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link VendorController}: a 201 on create, a 404 for an unknown vendor, and a
 * 400 for a blank name. Services mocked; fault mappings from {@link ApAdvice} + the shared {@link
 * ApiExceptionHandler}. The mirror of {@link CustomerControllerTest}.
 */
@WebMvcTest(VendorController.class)
@Import({ApAdvice.class, ApiExceptionHandler.class})
class VendorControllerTest {

  private static final UUID VENDOR = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private VendorWriter vendorWriter;
  @MockitoBean private VendorReader vendorReader;

  @Test
  void createReturns201() throws Exception {
    when(vendorWriter.create(any(), any(), any()))
        .thenReturn(new VendorResponse(VENDOR, "Acme Supplies", "ap@acme.test", null, true));

    mockMvc
        .perform(
            post("/api/v1/vendors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Supplies\",\"email\":\"ap@acme.test\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(VENDOR.toString()))
        .andExpect(jsonPath("$.name").value("Acme Supplies"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void getUnknownVendorIsAProblemDetail404() throws Exception {
    when(vendorReader.get(VENDOR)).thenThrow(new VendorNotFoundException(VENDOR));

    mockMvc
        .perform(get("/api/v1/vendors/{id}", VENDOR))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ap-not-found"));
  }

  @Test
  void createWithBlankNameIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/vendors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
