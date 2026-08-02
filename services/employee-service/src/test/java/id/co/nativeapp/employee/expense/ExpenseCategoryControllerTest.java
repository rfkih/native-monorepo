package id.co.nativeapp.employee.expense;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.config.EmployeeApiAdvice;
import id.co.nativeapp.employee.expense.controller.ExpenseCategoryController;
import id.co.nativeapp.employee.expense.domain.CategoryNotFoundException;
import id.co.nativeapp.employee.expense.domain.DuplicateCategoryNameException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.InvalidGlHintException;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
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
 * Web-slice test for {@link ExpenseCategoryController}: validation 400/422, 404/409 mappings, and
 * happy paths. Services mocked.
 */
@WebMvcTest(ExpenseCategoryController.class)
@Import({EmployeeApiAdvice.class, ApiExceptionHandler.class})
class ExpenseCategoryControllerTest {

  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ExpenseCategoryWriter categoryWriter;
  @MockitoBean private ExpenseCategoryReader categoryReader;

  private static ExpenseCategory sampleCategory() {
    return new ExpenseCategory("Supplies", "supplies", false);
  }

  @Test
  void listReturns200() throws Exception {
    when(categoryReader.list()).thenReturn(List.of());
    mockMvc.perform(get("/api/v1/expense-categories")).andExpect(status().isOk());
  }

  @Test
  void createReturns201WithLocation() throws Exception {
    when(categoryWriter.create(eq("Supplies"), eq("supplies"), eq(false)))
        .thenReturn(sampleCategory());

    mockMvc
        .perform(
            post("/api/v1/expense-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supplies\",\"glHint\":\"supplies\",\"taxable\":false}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Supplies"))
        .andExpect(jsonPath("$.glHint").value("supplies"));
  }

  @Test
  void createWithABlankNameIs400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/expense-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"glHint\":\"\",\"taxable\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWithAnInvalidGlHintIs422() throws Exception {
    when(categoryWriter.create(any(), eq("not-a-real-hint"), anyBoolean()))
        .thenThrow(new InvalidGlHintException("not-a-real-hint"));

    mockMvc
        .perform(
            post("/api/v1/expense-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bogus\",\"glHint\":\"not-a-real-hint\",\"taxable\":false}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-gl-hint"));
  }

  @Test
  void createADuplicateNameIs409() throws Exception {
    when(categoryWriter.create(eq("Supplies"), any(), anyBoolean()))
        .thenThrow(new DuplicateCategoryNameException("Supplies"));

    mockMvc
        .perform(
            post("/api/v1/expense-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Supplies\",\"glHint\":\"supplies\",\"taxable\":false}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/duplicate-expense-category-name"));
  }

  @Test
  void updateOfAnUnknownCategoryIs404() throws Exception {
    when(categoryWriter.update(eq(CATEGORY), any(), any(), any(), any()))
        .thenThrow(new CategoryNotFoundException(CATEGORY));

    mockMvc
        .perform(
            patch("/api/v1/expense-categories/" + CATEGORY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/expense-category-not-found"));
  }

  @Test
  void updateReturns200() throws Exception {
    when(categoryWriter.update(eq(CATEGORY), any(), any(), any(), any()))
        .thenReturn(sampleCategory());

    mockMvc
        .perform(
            patch("/api/v1/expense-categories/" + CATEGORY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Supplies"));
  }

  @Test
  void seedDefaultsReturns200WithTheFullList() throws Exception {
    when(categoryReader.list()).thenReturn(List.of());

    mockMvc.perform(post("/api/v1/expense-categories/seed-defaults")).andExpect(status().isOk());
  }
}
