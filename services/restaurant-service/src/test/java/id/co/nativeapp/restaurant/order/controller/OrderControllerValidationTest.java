package id.co.nativeapp.restaurant.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.service.OrderService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice validation tests for {@code POST /api/v1/orders} and {@code POST /api/v1/orders/quote}.
 *
 * <p>Proves bean-validation failures on the {@code @Valid CheckoutRequest} / {@code @Valid
 * QuoteRequest} body and domain {@link IllegalArgumentException}s (unknown item, inactive item,
 * currency mismatch) are mapped to RFC 7807 {@code 400 ProblemDetail} by {@link
 * ApiExceptionHandler}. A valid checkout returns {@code 201 Created} + {@code Location}; an
 * idempotent retry returns {@code 200 OK} without {@code Location}. A valid quote returns {@code
 * 200 OK} with the breakdown fields.
 *
 * <p>No DB — pure {@code @WebMvcTest} slice.
 */
@WebMvcTest(OrderController.class)
@Import(ApiExceptionHandler.class)
class OrderControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORDERS_PATH = "/api/v1/orders";
  private static final String QUOTE_PATH = "/api/v1/orders/quote";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrderService orderService;

  private static OrderResponse stubOrder(UUID orderId) {
    PriceBreakdownResponse breakdown =
        new PriceBreakdownResponse(30_000L, 0L, 1_500L, 3_150L, 34_650L, "IDR", false);
    return new OrderResponse(
        orderId,
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        34_650L,
        "IDR",
        UUID.randomUUID(),
        List.of(
            new OrderLineResponse(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Nasi Goreng",
                15_000L,
                2,
                30_000L)),
        null,
        breakdown);
  }

  private static PriceBreakdownResponse stubBreakdown() {
    return new PriceBreakdownResponse(30_000L, 0L, 1_500L, 3_150L, 34_650L, "IDR", false);
  }

  @Test
  void aNewOrderReturns201WithALocationHeader() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.checkout(any())).thenReturn(new CheckoutResult(stubOrder(orderId), true));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-001",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":2}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/orders/" + orderId))
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.totalMinor").value(34_650))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.lines").isArray())
        .andExpect(jsonPath("$.lines[0].name").value("Nasi Goreng"))
        .andExpect(jsonPath("$.breakdown.subtotalMinor").value(30_000))
        .andExpect(jsonPath("$.breakdown.discountMinor").value(0))
        .andExpect(jsonPath("$.breakdown.serviceChargeMinor").value(1_500))
        .andExpect(jsonPath("$.breakdown.taxMinor").value(3_150))
        .andExpect(jsonPath("$.breakdown.grandTotalMinor").value(34_650))
        .andExpect(jsonPath("$.breakdown.currency").value("IDR"))
        .andExpect(jsonPath("$.breakdown.usesIllustrativeRules").value(false));
  }

  @Test
  void anIdempotentRetryReturns200WithNoLocationHeader() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.checkout(any())).thenReturn(new CheckoutResult(stubOrder(orderId), false));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-001",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":2}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void missingBusinessIdIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"idempotencyKey":"ord-001",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":1}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("businessId"));
  }

  @Test
  void emptyLinesIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-001",
         "lines":[]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
  }

  @Test
  void qtyZeroIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-001",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":0}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines[0].qty"));
  }

  @Test
  void missingIdempotencyKeyIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":1}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));
  }

  @Test
  void unknownMenuItemIsRejectedWith400ProblemDetail() throws Exception {
    when(orderService.checkout(any()))
        .thenThrow(new IllegalArgumentException("Menu item not found or not visible: some-id"));
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-002",
         "lines":[{"menuItemId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","qty":1}]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }

  @Test
  void mixedCurrencyIsRejectedWith400ProblemDetail() throws Exception {
    when(orderService.checkout(any()))
        .thenThrow(
            new IllegalArgumentException(
                "All menu items in one order must share the same currency; found: [IDR, USD]"));
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "idempotencyKey":"ord-003",
         "lines":[
           {"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":1},
           {"menuItemId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","qty":1}
         ]}
        """;
    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }

  // -----------------------------------------------------------------------
  // Quote endpoint: POST /api/v1/orders/quote
  // -----------------------------------------------------------------------

  @Test
  void aValidQuoteReturns200WithBreakdownFields() throws Exception {
    when(orderService.quote(any())).thenReturn(stubBreakdown());

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":2}]}
        """;
    mockMvc
        .perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subtotalMinor").value(30_000))
        .andExpect(jsonPath("$.discountMinor").value(0))
        .andExpect(jsonPath("$.serviceChargeMinor").value(1_500))
        .andExpect(jsonPath("$.taxMinor").value(3_150))
        .andExpect(jsonPath("$.grandTotalMinor").value(34_650))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.usesIllustrativeRules").value(false));
  }

  @Test
  void quoteMissingBusinessIdIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":1}]}
        """;
    mockMvc
        .perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("businessId"));
  }

  @Test
  void quoteEmptyLinesIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","lines":[]}
        """;
    mockMvc
        .perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
  }

  @Test
  void quoteNegativeDiscountIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"menuItemId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","qty":1}],
         "discountMinor":-1}
        """;
    mockMvc
        .perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("discountMinor"));
  }

  @Test
  void quoteUnknownMenuItemIsRejectedWith400ProblemDetail() throws Exception {
    when(orderService.quote(any()))
        .thenThrow(new IllegalArgumentException("Menu item not found or not visible: some-id"));
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"menuItemId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","qty":1}]}
        """;
    mockMvc
        .perform(post(QUOTE_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }
}
