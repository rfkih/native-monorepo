package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.expense.domain.CategoryNotFoundException;
import id.co.nativeapp.employee.expense.domain.DuplicateCategoryNameException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.repository.ExpenseCategoryRepository;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Writer unit tests (mocked repository, no Spring context) for {@link ExpenseCategoryWriter}'s
 * duplicate-name race backstop (data-engineer review, alongside W1/W2/S1-S4): the {@code
 * existsByNameIgnoreCase} pre-check is itself a check-then-act race, so {@code create}/{@code
 * update} must translate a {@link DataIntegrityViolationException} from the {@code
 * uq_expense_category_name} unique index into the same {@link DuplicateCategoryNameException} the
 * pre-check throws, never a leaked raw exception.
 */
class ExpenseCategoryWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "owner-sub";

  private final ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
  private final ExpenseCategoryWriter writer = new ExpenseCategoryWriter(repository);

  @Test
  void createTranslatesARacedUniqueConstraintViolationIntoDuplicateCategoryName() throws Exception {
    when(repository.existsByNameIgnoreCase("Supplies")).thenReturn(false);
    when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.create("Supplies", "supplies", false)))
        .isInstanceOf(DuplicateCategoryNameException.class);
  }

  @Test
  void createSucceedsWhenNoRaceOccurs() throws Exception {
    when(repository.existsByNameIgnoreCase("Supplies")).thenReturn(false);
    when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    ExpenseCategory result =
        TenantContext.callAs(TENANT, ACTOR, () -> writer.create("Supplies", "supplies", false));

    assertThat(result.getName()).isEqualTo("Supplies");
  }

  @Test
  void createRejectsViaThePreCheckWhenTheNameAlreadyExists() throws Exception {
    when(repository.existsByNameIgnoreCase("Supplies")).thenReturn(true);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.create("Supplies", "supplies", false)))
        .isInstanceOf(DuplicateCategoryNameException.class);
  }

  @Test
  void updateTranslatesARacedUniqueConstraintViolationIntoDuplicateCategoryName() throws Exception {
    ExpenseCategory existing = new ExpenseCategory("Old Name", "", false);
    UUID id = existing.getId();
    when(repository.findById(id)).thenReturn(Optional.of(existing));
    when(repository.existsByNameIgnoreCase("New Name")).thenReturn(false);
    when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.update(id, "New Name", null, null, null)))
        .isInstanceOf(DuplicateCategoryNameException.class);
  }

  @Test
  void updateOfAnUnknownCategoryStillThrowsNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.update(id, "New Name", null, null, null)))
        .isInstanceOf(CategoryNotFoundException.class);
  }

  @Test
  void updateSucceedsWhenNoRaceOccurs() throws Exception {
    ExpenseCategory existing = new ExpenseCategory("Old Name", "", false);
    UUID id = existing.getId();
    when(repository.findById(id)).thenReturn(Optional.of(existing));
    when(repository.existsByNameIgnoreCase("New Name")).thenReturn(false);
    when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    ExpenseCategory result =
        TenantContext.callAs(TENANT, ACTOR, () -> writer.update(id, "New Name", null, null, null));

    assertThat(result.getName()).isEqualTo("New Name");
  }
}
