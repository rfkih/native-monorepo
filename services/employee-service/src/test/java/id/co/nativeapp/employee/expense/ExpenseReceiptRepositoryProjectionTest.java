package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.expense.projection.ReceiptMetaView;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * A reflection-based guard (no Spring context, no database) proving the CODE-STRUCTURE §3.3
 * projection discipline for receipts: the metadata read never selects the {@code data} blob column,
 * and the projection interface itself carries no accessor that could expose it. Mirrors the "list
 * projections never leak PII/blob" convention already documented on {@code
 * PayslipLineRepository}/{@code CompensationView}, made executable here.
 */
class ExpenseReceiptRepositoryProjectionTest {

  @Test
  void receiptMetaViewHasNoDataAccessor() {
    for (Method method : ReceiptMetaView.class.getMethods()) {
      assertThat(method.getName()).isNotEqualToIgnoringCase("getData");
    }
  }

  @Test
  void findMetaByClaimIdNativeSqlNeverSelectsTheDataColumn() throws NoSuchMethodException {
    Method method =
        ExpenseReceiptRepository.class.getMethod("findMetaByClaimId", java.util.UUID.class);
    Query query = method.getAnnotation(Query.class);

    assertThat(query).isNotNull();
    assertThat(query.nativeQuery()).isTrue();
    assertThat(query.value()).doesNotContainIgnoringCase("data");
    // The exact column list the projection expects — locks the query shape, not just an absence.
    assertThat(query.value())
        .containsIgnoringCase("id AS id")
        .containsIgnoringCase("content_type AS content_type")
        .containsIgnoringCase("byte_size AS byte_size")
        .containsIgnoringCase("sha256 AS sha256");
  }

  @Test
  void deleteByClaimIdIsAlsoNativeAndModifying() throws NoSuchMethodException {
    Method method =
        ExpenseReceiptRepository.class.getMethod("deleteByClaimId", java.util.UUID.class);
    Query query = method.getAnnotation(Query.class);

    assertThat(query).isNotNull();
    assertThat(query.nativeQuery()).isTrue();
    assertThat(method.getAnnotation(org.springframework.data.jpa.repository.Modifying.class))
        .isNotNull();
  }

  @Test
  void findMetaByClaimIdNeverUsesAWildcardSelect() throws NoSuchMethodException {
    // Belt-and-braces: the query text must never be a bare `SELECT *` shape either.
    Method method =
        ExpenseReceiptRepository.class.getMethod("findMetaByClaimId", java.util.UUID.class);
    Query query = method.getAnnotation(Query.class);

    String normalized = query.value().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    assertThat(normalized).doesNotContain("select *");
  }
}
