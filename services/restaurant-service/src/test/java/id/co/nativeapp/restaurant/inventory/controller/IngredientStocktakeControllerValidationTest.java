package id.co.nativeapp.restaurant.inventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeResponse;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
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
 * Web-slice validation tests for {@code /api/v1/ingredient-stocktakes} (ADR 0046 phase 1): the
 * {@code Idempotency-Key} guards (missing/blank → 400, over-length → 400), bean-validation on the
 * {@code @Valid} body, and the 201/200 replay status shapes. Mirrors {@code
 * stocktake.controller.StocktakeController}'s (untested-directly) contract via the same idioms as
 * {@code order.controller.OrderControllerValidationTest}. No DB — pure {@code @WebMvcTest} slice.
 */
@WebMvcTest(IngredientStocktakeController.class)
@Import(ApiExceptionHandler.class)
class IngredientStocktakeControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String PATH = "/api/v1/ingredient-stocktakes";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private IngredientStocktakeService service;

  private static IngredientStocktakeResponse stubResponse(UUID id) {
    return new IngredientStocktakeResponse(
        id, BUSINESS_ID, "IDR", Instant.now(), 15_000L, List.of());
  }

  @Test
  void aFreshSubmitReturns201WithALocationHeader() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.submit(any(), anyString()))
        .thenReturn(new SubmitIngredientStocktakeResult(stubResponse(id), true));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "ist-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/ingredient-stocktakes/" + id))
        .andExpect(jsonPath("$.shrinkageMinor").value(15_000));
  }

  @Test
  void aSameKeyReplayReturns200WithNoLocationHeader() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.submit(any(), anyString()))
        .thenReturn(new SubmitIngredientStocktakeResult(stubResponse(id), false));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "ist-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void missingIdempotencyKeyIsRejectedWith400() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }

  @Test
  void blankIdempotencyKeyIsRejectedWith400() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void tooLongIdempotencyKeyIsRejectedWith400() throws Exception {
    String tooLong = "k".repeat(65);
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", tooLong)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  void missingBusinessIdIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":18}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "ist-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("businessId"));
  }

  @Test
  void emptyLinesIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","lines":[]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "ist-003")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
  }

  @Test
  void negativeCountedQtyIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222",
         "lines":[{"ingredientId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","countedQty":-1}]}
        """;
    mockMvc
        .perform(
            post(PATH)
                .header("Idempotency-Key", "ist-004")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("lines[0].countedQty"));
  }

  @Test
  void getByIdReturns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.findById(id)).thenReturn(stubResponse(id));

    mockMvc
        .perform(get(PATH + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()));
  }

  @Test
  void historyReturns200WithAJsonArray() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.history(BUSINESS_ID)).thenReturn(List.of(stubResponse(id)));

    mockMvc
        .perform(get(PATH).param("businessId", BUSINESS_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()));
  }
}
