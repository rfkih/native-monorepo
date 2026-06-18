package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Deterministic proof of {@link SaleService}'s conflict-recovery branch — the one a concurrent
 * insert race triggers — without depending on thread timing.
 *
 * <p>When {@link SaleWriter#create} throws a {@link DataIntegrityViolationException} (the
 * unique-constraint loser), {@code recordSale} must NOT propagate a 500: it re-reads via {@link
 * SaleWriter#findExistingByKey} (a fresh transaction) and returns the existing sale with {@code
 * created=false}. If that re-read finds nothing — a genuine integrity error, not a concurrency race
 * — the original exception propagates.
 */
class SaleServiceConflictRecoveryTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-a";

  private final SaleWriter writer = mock(SaleWriter.class);
  private final SaleService service = new SaleService(writer);

  private final RecordSaleCommand command =
      new RecordSaleCommand(
          UUID.fromString("22222222-2222-2222-2222-222222222222"),
          1_500_000L,
          "IDR",
          Instant.parse("2026-06-14T08:30:00Z"),
          "k");

  @Test
  void aUniqueConstraintConflictRecoversTheExistingSaleAsIdempotent() throws Exception {
    Sale existing =
        new Sale(
            command.businessId(), Money.ofMinor(1_500_000L, "IDR"),
            command.occurredAt(), command.idempotencyKey());
    SaleResponse existingResponse = SaleResponse.from(existing);
    when(writer.create(any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.findExistingByKey(command.idempotencyKey()))
        .thenReturn(Optional.of(existingResponse));

    RecordSaleResult result =
        TenantContext.callAs(TENANT, ACTOR, () -> service.recordSale(command));

    assertThat(result.created()).isFalse();
    assertThat(result.sale()).isEqualTo(existingResponse);
  }

  @Test
  void aConflictWithNoRecoverableRowRethrows() throws Exception {
    when(writer.create(any())).thenThrow(new DataIntegrityViolationException("dup key"));
    when(writer.findExistingByKey(command.idempotencyKey())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> service.recordSale(command)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
