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
 * Web-slice validation tests for {@code POST /api/v1/orders}.
 *
 * <p>Proves bean-validation failures on the {@code @Valid CheckoutRequest} body and domain {@link
 * IllegalArgumentException}s (unknown item, inactive item, currency mismatch) are mapped to RFC
 * 7807 {@code 400 ProblemDetail} by {@link ApiExceptionHandler}. A valid checkout returns {@code
 * 201 Created} + {@code Location}; an idempotent retry returns {@code 200 OK} without {@code
 * Location}.
 *
 * <p>No DB — pure {@code @WebMvcTest} slice.
 */
@WebMvcTest(OrderController.class)
@Import(ApiExceptionHandler.class)
class OrderControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORDERS_PATH = "/api/v1/orders";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrderService orderService;

  private static OrderResponse stubOrder(UUID orderId) {
    return new OrderResponse(
        orderId,
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        30_000L,
        "IDR",
        UUID.randomUUID(),
        List.of(
            new OrderLineResponse(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Nasi Goreng",
                15_000L,
                2,
                30_000L)));
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
        .andExpect(jsonPath("$.totalMinor").value(30_000))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.lines").isArray())
        .andExpect(jsonPath("$.lines[0].name").value("Nasi Goreng"));
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
}
