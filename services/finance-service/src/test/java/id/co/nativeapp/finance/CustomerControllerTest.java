package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.ar.controller.ArAdvice;
import id.co.nativeapp.finance.ar.controller.CustomerController;
import id.co.nativeapp.finance.ar.domain.CustomerNotFoundException;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.service.CustomerReader;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
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
 * Web-slice test for {@link CustomerController}: a 201 on create, a 404 for an unknown customer,
 * and a 400 for a blank name. Services mocked; fault mappings from {@link ArAdvice} + the shared
 * {@link ApiExceptionHandler}.
 */
@WebMvcTest(CustomerController.class)
@Import({ArAdvice.class, ApiExceptionHandler.class})
class CustomerControllerTest {

  private static final UUID CUSTOMER = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CustomerWriter customerWriter;
  @MockitoBean private CustomerReader customerReader;

  @Test
  void createReturns201() throws Exception {
    when(customerWriter.create(any(), any(), any()))
        .thenReturn(new CustomerResponse(CUSTOMER, "Acme Corp", "ap@acme.test", null, true));

    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme Corp\",\"email\":\"ap@acme.test\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(CUSTOMER.toString()))
        .andExpect(jsonPath("$.name").value("Acme Corp"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void getUnknownCustomerIsAProblemDetail404() throws Exception {
    when(customerReader.get(CUSTOMER)).thenThrow(new CustomerNotFoundException(CUSTOMER));

    mockMvc
        .perform(get("/api/v1/customers/{id}", CUSTOMER))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ar-not-found"));
  }

  @Test
  void createWithBlankNameIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
