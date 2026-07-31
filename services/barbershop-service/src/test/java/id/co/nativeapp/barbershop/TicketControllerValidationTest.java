package id.co.nativeapp.barbershop;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.barbershop.config.TicketAdvice;
import id.co.nativeapp.barbershop.ticket.controller.TicketController;
import id.co.nativeapp.barbershop.ticket.domain.MixedCurrencyException;
import id.co.nativeapp.barbershop.ticket.domain.OfflineReplayInvalidException;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketPaymentResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web-slice tests for {@code /api/v1/barbershop/tickets} — the controller + the shared RFC-7807
 * {@link ApiExceptionHandler} advice plus the ticket-local {@link TicketAdvice}, no DB. {@link
 * TicketService} is mocked so the slice is pure web; the business-rule rejections
 * (unknown/inactive/ cross-business item, cash short-tender) are simulated by stubbing the service
 * to throw the same exception the real {@code TicketItemReader}/{@code CashProvider} raise, proving
 * the HTTP mapping. Ported from carwash-service's {@code TicketControllerValidationTest} (ADR
 * 0024).
 *
 * <p><strong>Domain differences exercised here:</strong> {@code chair} is OPTIONAL (unlike
 * carwash's mandatory {@code bay}) — a request without it must NOT 400; {@code staffProfileId} is
 * MANDATORY (ADR 0024 — every cut has a barber) — a request without it MUST 400.
 */
@WebMvcTest(TicketController.class)
@Import({ApiExceptionHandler.class, TicketAdvice.class})
class TicketControllerValidationTest {

  private static final String OUTLET = "22222222-2222-2222-2222-222222222222";
  private static final String ITEM = "33333333-3333-3333-3333-333333333333";
  private static final String STAFF_PROFILE = "44444444-4444-4444-4444-444444444444";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TicketService ticketService;

  @Test
  void aMissingStaffProfileIdIsRejectedWith400() throws Exception {
    // ADR 0024: every cut has a barber — staffProfileId is @NotNull at the DTO boundary.
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "chair": "chair-1",
          "lines": [{"itemType":"SERVICE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, ITEM);
    mockMvc
        .perform(
            post("/api/v1/barbershop/tickets/checkout")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void aMissingChairIsAcceptedSinceChairsAreOptional() throws Exception {
    // Unlike carwash's mandatory `bay`, `chair` is nullable — a barbershop may run without
    // assigned chairs.
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenReturn(validCheckoutResult());
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "staffProfileId": "%s",
          "lines": [{"itemType":"SERVICE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, STAFF_PROFILE, ITEM);
    mockMvc
        .perform(
            post("/api/v1/barbershop/tickets/checkout")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated());
  }

  @Test
  void emptyLinesAreRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "chair": "chair-1",
          "staffProfileId": "%s",
          "lines": [],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, STAFF_PROFILE);
    mockMvc
        .perform(
            post("/api/v1/barbershop/tickets/checkout")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aMissingIdempotencyKeyIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "chair": "chair-1",
          "staffProfileId": "%s",
          "lines": [{"itemType":"SERVICE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, STAFF_PROFILE, ITEM);
    mockMvc
        .perform(
            post("/api/v1/barbershop/tickets/checkout")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aMissingTenderTypeIsRejectedWith400() throws Exception {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "chair": "chair-1",
          "staffProfileId": "%s",
          "lines": [{"itemType":"SERVICE","itemId":"%s","qty":1}],
          "payment": {"tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, STAFF_PROFILE, ITEM);
    mockMvc
        .perform(
            post("/api/v1/barbershop/tickets/checkout")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item not found: SERVICE " + ITEM));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void anInactiveItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item is inactive: SERVICE " + ITEM));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void aCrossBusinessItemIsRejectedWith400() throws Exception {
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("Catalog item belongs to a different business"));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void mixedCurrencyLinesAreRejectedWith422() throws Exception {
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new MixedCurrencyException(Set.of("IDR", "USD")));
    mockMvc
        .perform(checkoutRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ticket-mixed-currency"));
  }

  @Test
  void offlineReplayInvalidIsRejectedWith422() throws Exception {
    // Phase 5 (ADR 0028): any OfflineReplayGuard rejection (bounds, pairing, forbidden fields)
    // surfaces as this SAME exception type; the HTTP mapping is what this slice proves.
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new OfflineReplayInvalidException("clientOccurredAt is more than 48h past"));
    mockMvc
        .perform(checkoutRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/ticket-offline-replay-invalid"));
  }

  @Test
  void cashShortTenderIsRejectedWith400() throws Exception {
    when(ticketService.checkout(org.mockito.ArgumentMatchers.any(CheckoutRequest.class)))
        .thenThrow(new IllegalArgumentException("tendered amount is less than the amount due"));
    mockMvc.perform(checkoutRequest()).andExpect(status().isBadRequest());
  }

  @Test
  void captureOfAnUnknownTicketIsRejectedWith404() throws Exception {
    UUID id = UUID.randomUUID();
    when(ticketService.capture(org.mockito.ArgumentMatchers.eq(id)))
        .thenThrow(new TicketNotFoundException(id));
    mockMvc
        .perform(post("/api/v1/barbershop/tickets/" + id + "/capture"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/ticket-not-found"));
  }

  private static MockHttpServletRequestBuilder checkoutRequest() {
    String body =
        """
        {
          "businessId": "%s",
          "idempotencyKey": "k1",
          "chair": "chair-1",
          "staffProfileId": "%s",
          "lines": [{"itemType":"SERVICE","itemId":"%s","qty":1}],
          "payment": {"tenderType":"CASH","tenderedMinor":100000}
        }
        """
            .formatted(OUTLET, STAFF_PROFILE, ITEM);
    return post("/api/v1/barbershop/tickets/checkout")
        .contentType("application/json")
        .content(body);
  }

  private static CheckoutResult validCheckoutResult() {
    UUID ticketId = UUID.randomUUID();
    TicketResponse ticket =
        new TicketResponse(
            ticketId,
            UUID.fromString(OUTLET),
            null,
            UUID.fromString(STAFF_PROFILE),
            "Budi",
            ticketId,
            new PriceBreakdownResponse(100_000_00L, 0L, 0L, 11_000_00L, 111_000_00L, "IDR", true),
            Instant.now(),
            List.of(),
            new TicketPaymentResponse(
                UUID.randomUUID(),
                ticketId,
                "CASH",
                "CAPTURED",
                111_000_00L,
                "IDR",
                111_000_00L,
                0L,
                false,
                ticketId));
    return new CheckoutResult(ticket, true);
  }
}
