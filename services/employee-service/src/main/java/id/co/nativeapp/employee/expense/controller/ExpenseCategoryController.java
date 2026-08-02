package id.co.nativeapp.employee.expense.controller;

import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.dto.CreateCategoryRequest;
import id.co.nativeapp.employee.expense.dto.ExpenseCategoryResponse;
import id.co.nativeapp.employee.expense.dto.UpdateCategoryRequest;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryReader;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/expense-categories} — the admin catalog surface (ADR 0030): active categories,
 * create, partial update, and the idempotent default-set seed.
 */
@Tag(name = "Expense Categories", description = "The admin catalog a claim is raised against")
@RestController
@RequestMapping("/api/v1/expense-categories")
public class ExpenseCategoryController {

  private final ExpenseCategoryWriter categoryWriter;
  private final ExpenseCategoryReader categoryReader;

  public ExpenseCategoryController(
      ExpenseCategoryWriter categoryWriter, ExpenseCategoryReader categoryReader) {
    this.categoryWriter = categoryWriter;
    this.categoryReader = categoryReader;
  }

  @Operation(summary = "List active expense categories")
  @GetMapping
  public List<ExpenseCategoryResponse> list() {
    return categoryReader.list();
  }

  @Operation(summary = "Create an expense category")
  @PostMapping
  public ResponseEntity<ExpenseCategoryResponse> create(
      @Valid @RequestBody CreateCategoryRequest request) {
    ExpenseCategory category =
        categoryWriter.create(request.name(), request.glHint(), request.taxable());
    ExpenseCategoryResponse body = ExpenseCategoryResponse.from(category);
    return ResponseEntity.created(URI.create("/api/v1/expense-categories/" + body.id())).body(body);
  }

  @Operation(summary = "Update an expense category (partial — null fields are unchanged)")
  @PatchMapping("/{id}")
  public ExpenseCategoryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
    ExpenseCategory category =
        categoryWriter.update(
            id, request.name(), request.glHint(), request.taxable(), request.active());
    return ExpenseCategoryResponse.from(category);
  }

  @Operation(summary = "Seed the tenant's default expense categories (idempotent)")
  @PostMapping("/seed-defaults")
  public List<ExpenseCategoryResponse> seedDefaults() {
    categoryWriter.seedDefaults();
    return categoryReader.list();
  }
}
