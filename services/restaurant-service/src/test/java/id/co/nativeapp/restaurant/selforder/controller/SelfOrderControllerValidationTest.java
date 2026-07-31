package id.co.nativeapp.restaurant.selforder.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.selforder.service.SelfOrderService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice validation tests for {@code POST /api/v1/self-order/orders} — the anonymous cart's
 * INPUT BOUNDS (security review F-2): {@code lines} capped at {@value
 * id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest#MAX_LINES} and each line's {@code
 * qty} capped at {@value id.co.nativeapp.restaurant.selforder.dto.SelfOrderLineBounds#MAX_QTY} —
 * bean-validation failures mapped to RFC 7807 by {@link ApiExceptionHandler}, exactly mirroring
 * {@code order.controller.OrderControllerValidationTest}.
 *
 * <p>This is deliberately the ONLY place the DTO's bean validation is actually exercised: {@code
 * selforder.SelfOrderCreateGateTest}/{@code SelfOrderCapTest}/{@code SelfOrderSweepTest} call
 * {@link SelfOrderService} directly (bypassing the {@code @Valid @RequestBody} boundary), so a
 * crafted body that violates {@code @Size}/{@code @Max} would never be rejected without a
 * controller-level ({@code @WebMvcTest}) test.
 *
 * <p>No DB — pure {@code @WebMvcTest} slice; the anonymous token filter is irrelevant here since
 * bean validation runs before the (mocked) service is ever called.
 */
@WebMvcTest(SelfOrderController.class)
@Import(ApiExceptionHandler.class)
class SelfOrderControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String ORDERS_PATH = "/api/v1/self-order/orders";
  private static final UUID MENU_ITEM_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SelfOrderService selfOrderService;

  private static OrderResponse stubOrder() {
    PriceBreakdownResponse breakdown =
        new PriceBreakdownResponse(15_000L, 0L, 0L, 0L, 15_000L, "IDR", true);
    return new OrderResponse(
        UUID.randomUUID(),
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        15_000L,
        "IDR",
        null,
        List.of(new OrderLineResponse(MENU_ITEM_ID, "Es Teh", 15_000L, 1, 15_000L)),
        null,
        breakdown);
  }

  @Test
  void aCartAtTheQtyCeilingOf99IsAccepted() throws Exception {
    when(selfOrderService.createOrder(any())).thenReturn(stubOrder());

    String body =
        """
        {"idempotencyKey":"k-1","lines":[{"menuItemId":"%s","qty":99}]}
        """
            .formatted(MENU_ITEM_ID);

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
  }

  @Test
  void qtyOver99IsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"idempotencyKey":"k-1","lines":[{"menuItemId":"%s","qty":100}]}
        """
            .formatted(MENU_ITEM_ID);

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines[0].qty"));
  }

  @Test
  void qtyZeroIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"idempotencyKey":"k-1","lines":[{"menuItemId":"%s","qty":0}]}
        """
            .formatted(MENU_ITEM_ID);

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines[0].qty"));
  }

  @Test
  void moreThan100LinesIsRejectedWithAProblemDetail() throws Exception {
    String lines =
        IntStream.range(0, 101)
            .mapToObj(i -> "{\"menuItemId\":\"%s\",\"qty\":1}".formatted(MENU_ITEM_ID))
            .collect(Collectors.joining(","));
    String body = "{\"idempotencyKey\":\"k-1\",\"lines\":[" + lines + "]}";

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
  }

  @Test
  void emptyLinesIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"idempotencyKey":"k-1","lines":[]}
        """;

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
  }

  @Test
  void missingIdempotencyKeyIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"lines":[{"menuItemId":"%s","qty":1}]}
        """
            .formatted(MENU_ITEM_ID);

    mockMvc
        .perform(post(ORDERS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));
  }
}
