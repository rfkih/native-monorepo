package id.co.nativeapp.restaurant.inventory.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.inventory.domain.IngredientNameConflictException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit pins for {@link IngredientService}'s integrity-violation mapping (review finding, ADR 0046):
 * the V31 partial-unique name collision becomes a clean 409 domain fault, while any OTHER integrity
 * violation is rethrown untouched.
 */
class IngredientServiceTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("5f5e0167-ee70-45b8-8afe-019e8129e659");

  private final IngredientWriter writer = mock(IngredientWriter.class);
  private final IngredientReader reader = mock(IngredientReader.class);
  private final IngredientService service = new IngredientService(writer, reader);

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "test", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static CreateIngredientRequest request() {
    return new CreateIngredientRequest(OUTLET, "Roti", "pcs", null, null, null, null);
  }

  @Test
  void createMapsTheNameUniqueViolationToANameConflict() {
    when(writer.create(any()))
        .thenThrow(
            new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint"
                    + " \"uq_ingredient_company_business_name\""));

    assertThatThrownBy(() -> asTenant(() -> service.create(request())))
        .isInstanceOf(IngredientNameConflictException.class)
        .hasMessageContaining("Roti");
  }

  @Test
  void createRethrowsAnUnrelatedIntegrityViolationUntouched() {
    DataIntegrityViolationException other =
        new DataIntegrityViolationException("ck_ingredient_cost_both_or_neither violated");
    when(writer.create(any())).thenThrow(other);

    assertThatThrownBy(() -> asTenant(() -> service.create(request()))).isSameAs(other);
  }
}
