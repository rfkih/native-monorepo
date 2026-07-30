package id.co.nativeapp.carwash;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.carwash.config.TicketAdvice;
import id.co.nativeapp.carwash.ticket.controller.TicketController;
import id.co.nativeapp.carwash.ticket.domain.MixedCurrencyException;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@code /api/v1/carwash/tickets} — the controller + the shared RFC-7807 {@link
 * ApiExceptionHandler} advice plus the ticket-local {@link TicketAdvice}, no DB. {@link
 * TicketService} is mocked so the slice is pure web; the business-rule rejections (unknown/inactive/
 * cross-business item, cash short-tender) are simulated by stubbing the service to throw the same
 * exception the real {@code TicketItemReader}/{@code CashProvider} raise, proving the HTTP mapping.
 */
@WebMvcTest(TicketController.class)
@Import({ApiExceptionHandler.class, TicketAdvice.class})
class TicketControllerValidationTest {

  private static final String OUTLET = "22222222-2222-2222-2222-222222222222";
  private static final String ITEM = "33333333-3333-3333-3333-333333333333";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TicketService ticketService;

  @Test
  void aMissingBayIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "lines": [{"itemType":"PACKAGE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, ITEM);
    mockMvc
        .perform(post("/api/v1/carwash/tickets/checkout").contentType("application/json").content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void emptyLinesAreRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "bay": "bay-1",
          "lines": [],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET);
    mockMvc
        .perform(post("/api/v1/carwash/tickets/checkout").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aMissingIdempotencyKeyIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "bay": "bay-1",
          "lines": [{"itemType":"PACKAGE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, ITEM);
    mockMvc
        .perform(post("/api/v1/carwash/tickets/checkout").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aMissingTenderTypeIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "bay": "bay-1",
          "lines": [{"itemType":"PACKAGE","itemId":"%s","qty":1}],
          "payment": {"tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, ITEM);
    mockMvc
        .perform(post("/api/v1/carwash/tickets/checkout").contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item not found: PACKAGE " + ITEM));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void anInactiveItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item is inactive: PACKAGE " + ITEM));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void aCrossBusinessItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item belongs to a different business"));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void mixedCurrencyLinesAreRejectedWith422() throws Exception {
    when(ticketService.checkout(ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new MixedCurrencyException(Set.of("IDR", "USD")));
    mockMvc
        .perform(checkoutRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ticket-mixed-currency"));
  }

  @Test
  void cashShortTenderIsRejectedWith400() throws Exception {
    when(ticketService.checkout(ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("tendered amount is less than the amount due"));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void captureOfAnUnknownTicketIsRejectedWith404() throws Exception {
    UUID id = UUID.randomUUID();
    when(ticketService.capture(ArgumentMatchers.eq(id))).thenThrow(new TicketNotFoundException(id));
    mockMvc
        .perform(post("/api/v1/carwash/tickets/" + id + "/capture"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ticket-not-found"));
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      checkoutRequest() {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "bay": "bay-1",
          "lines": [{"itemType":"PACKAGE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, ITEM);
    return post("/api/v1/carwash/tickets/checkout").contentType("application/json").content(body);
  }
}
