package id.co.nativeapp.restaurant.inventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
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
 * Web-slice validation tests for {@code /api/v1/ingredients} (ADR 0046 phase 1): bean-validation
 * failures on the {@code @Valid} request bodies mapped to RFC 7807 {@code 400 ProblemDetail} by
 * {@link ApiExceptionHandler}, and the success-path status/Location shapes. Mirrors {@code
 * order.controller.OrderControllerValidationTest}. No DB — pure {@code @WebMvcTest} slice.
 */
@WebMvcTest(IngredientController.class)
@Import(ApiExceptionHandler.class)
class IngredientControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String INGREDIENTS_PATH = "/api/v1/ingredients";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private IngredientService ingredientService;

  private static IngredientResponse stubIngredient(UUID id) {
    return new IngredientResponse(id, BUSINESS_ID, "Patty", "pcs", 0, null, null, 0L, true);
  }

  @Test
  void listReturns200WithAJsonArray() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.findByBusiness(BUSINESS_ID)).thenReturn(List.of(stubIngredient(id)));

    mockMvc
        .perform(get(INGREDIENTS_PATH).param("businessId", BUSINESS_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("Patty"));
  }

  @Test
  void createReturns201WithALocationHeader() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.create(any())).thenReturn(stubIngredient(id));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","name":"Patty","unit":"pcs"}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/ingredients/" + id))
        .andExpect(jsonPath("$.name").value("Patty"))
        .andExpect(jsonPath("$.unit").value("pcs"));
  }

  @Test
  void createMissingBusinessIdIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"name":"Patty","unit":"pcs"}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("businessId"));
  }

  @Test
  void createBlankNameIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","name":"","unit":"pcs"}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void createBlankUnitIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","name":"Patty","unit":""}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("unit"));
  }

  @Test
  void createNegativeUnitCostIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","name":"Patty","unit":"pcs",
         "unitCostMinor":-1,"costCurrency":"IDR"}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("unitCostMinor"));
  }

  @Test
  void createBothCostFieldsPresentReturns201() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.create(any()))
        .thenReturn(
            new IngredientResponse(id, BUSINESS_ID, "Patty", "pcs", 0, 5_000L, "IDR", 0L, true));

    String body =
        """
        {"businessId":"22222222-2222-2222-2222-222222222222","name":"Patty","unit":"pcs",
         "unitCostMinor":5000,"costCurrency":"IDR"}
        """;
    mockMvc
        .perform(post(INGREDIENTS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.unitCostMinor").value(5000))
        .andExpect(jsonPath("$.costCurrency").value("IDR"));
  }

  @Test
  void patchReturns200WithTheUpdatedIngredient() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.update(org.mockito.ArgumentMatchers.eq(id), any()))
        .thenReturn(
            new IngredientResponse(id, BUSINESS_ID, "Patty Sapi", "pcs", 0, null, null, 0L, true));

    String body =
        """
        {"name":"Patty Sapi"}
        """;
    mockMvc
        .perform(
            patch(INGREDIENTS_PATH + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Patty Sapi"));
  }

  @Test
  void patchNegativeUnitCostIsRejectedWithAProblemDetail() throws Exception {
    UUID id = UUID.randomUUID();
    String body =
        """
        {"unitCostMinor":-5}
        """;
    mockMvc
        .perform(
            patch(INGREDIENTS_PATH + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("unitCostMinor"));
  }

  @Test
  void deleteReturns204NoContent() throws Exception {
    UUID id = UUID.randomUUID();
    mockMvc.perform(delete(INGREDIENTS_PATH + "/" + id)).andExpect(status().isNoContent());
  }

  @Test
  void setStockReturns200WithTheUpdatedIngredient() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.setStock(org.mockito.ArgumentMatchers.eq(id), anyInt()))
        .thenReturn(
            new IngredientResponse(id, BUSINESS_ID, "Patty", "pcs", 25, null, null, 0L, true));

    String body =
        """
        {"quantity":25}
        """;
    mockMvc
        .perform(
            put(INGREDIENTS_PATH + "/" + id + "/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockQty").value(25));
  }

  @Test
  void setStockMissingQuantityIsRejectedWithAProblemDetail() throws Exception {
    UUID id = UUID.randomUUID();
    String body = "{}";
    mockMvc
        .perform(
            put(INGREDIENTS_PATH + "/" + id + "/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("quantity"));
  }

  @Test
  void setStockNegativeQuantityIsRejectedWithAProblemDetail() throws Exception {
    UUID id = UUID.randomUUID();
    String body =
        """
        {"quantity":-1}
        """;
    mockMvc
        .perform(
            put(INGREDIENTS_PATH + "/" + id + "/stock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("quantity"));
  }

  @Test
  void addStockReturns200WithTheUpdatedIngredient() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.addStock(
            org.mockito.ArgumentMatchers.eq(id), anyInt(), any(), any()))
        .thenReturn(
            new IngredientResponse(id, BUSINESS_ID, "Patty", "pcs", 15, null, null, 0L, true));

    String body =
        """
        {"amount":5}
        """;
    mockMvc
        .perform(
            post(INGREDIENTS_PATH + "/" + id + "/stock/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockQty").value(15));
  }

  @Test
  void addStockWithAPricePassesTheMovingAverageFieldsThrough() throws Exception {
    UUID id = UUID.randomUUID();
    when(ingredientService.addStock(
            org.mockito.ArgumentMatchers.eq(id),
            org.mockito.ArgumentMatchers.eq(5),
            org.mockito.ArgumentMatchers.eq(130_000L),
            org.mockito.ArgumentMatchers.eq("IDR")))
        .thenReturn(
            new IngredientResponse(
                id, BUSINESS_ID, "Patty", "pcs", 15, 9_000L, "IDR", 135_000L, true));

    String body =
        """
        {"amount":5,"amountPaidMinor":130000,"costCurrency":"IDR"}
        """;
    mockMvc
        .perform(
            post(INGREDIENTS_PATH + "/" + id + "/stock/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unitCostMinor").value(9000))
        .andExpect(jsonPath("$.stockValueMinor").value(135000));
  }

  @Test
  void addStockWithNegativeAmountPaidIsRejectedWithAProblemDetail() throws Exception {
    UUID id = UUID.randomUUID();
    String body =
        """
        {"amount":5,"amountPaidMinor":-1,"costCurrency":"IDR"}
        """;
    mockMvc
        .perform(
            post(INGREDIENTS_PATH + "/" + id + "/stock/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("amountPaidMinor"));
  }

  @Test
  void addStockMissingAmountIsRejectedWithAProblemDetail() throws Exception {
    UUID id = UUID.randomUUID();
    String body = "{}";
    mockMvc
        .perform(
            post(INGREDIENTS_PATH + "/" + id + "/stock/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("amount"));
  }
}
