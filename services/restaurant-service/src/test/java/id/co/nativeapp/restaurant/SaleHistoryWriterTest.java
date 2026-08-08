package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.register.service.CashWindowLock;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.projection.SaleHistoryView;
import id.co.nativeapp.restaurant.sale.repository.SaleRepository;
import id.co.nativeapp.restaurant.sale.service.PostOutboxHook;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.security.OperatorContextProvider;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Unit pin for {@link SaleWriter#findHistory} — the cashier's "today's transactions" list ({@code
 * GET /api/v1/sales}). Mirrors {@code SalesChannelWriterTest}'s style: a mocked repository +
 * collaborators, no Spring context, proving the {@link SaleHistoryView} projection maps onto {@link
 * SaleHistoryResponse} correctly (including the CHAR(3) currency strip and the business/from/to
 * arguments threaded through to the repository).
 */
class SaleHistoryWriterTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";

  private final SaleRepository repository = mock(SaleRepository.class);
  private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
  private final PostOutboxHook postOutboxHook = mock(PostOutboxHook.class);
  private final OutletAccessGuard outletAccessGuard = mock(OutletAccessGuard.class);
  private final CashWindowLock cashWindowLock = mock(CashWindowLock.class);
  // A real (not mocked) instance: outside a bound HTTP request it always resolves to
  // Optional.empty() (no OperatorPrincipal) — exactly what this unaffected-by-ADR-0049 test needs.
  private final OperatorContextProvider operatorContextProvider = new OperatorContextProvider();
  private final SaleWriter writer =
      new SaleWriter(
          repository,
          outboxWriter,
          postOutboxHook,
          outletAccessGuard,
          cashWindowLock,
          operatorContextProvider);

  private static <T> T asTenant(Callable<T> action) {
    try {
      return TenantContext.callAs(COMPANY, "cashier-a", action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void mapsTheProjectionOntoTheHistoryResponseIncludingTheOrderIdAndStrippedCurrency() {
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID saleId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-08-08T02:00:00Z");
    Instant from = Instant.parse("2026-08-08T00:00:00Z");
    Instant to = Instant.parse("2026-08-09T00:00:00Z");

    SaleHistoryView view = mock(SaleHistoryView.class);
    when(view.getId()).thenReturn(saleId);
    when(view.getOrderId()).thenReturn(orderId);
    when(view.getOccurredAt()).thenReturn(occurredAt);
    when(view.getAmountMinor()).thenReturn(150_000L);
    // Postgres CHAR(3) is right-padded on read; the mapper must strip it (mirrors toResponse).
    when(view.getCurrency()).thenReturn("IDR");
    when(view.getTenderType()).thenReturn("CASH");
    when(view.getChannelCode()).thenReturn(null);
    when(repository.findHistory(businessId, from, to)).thenReturn(List.of(view));

    List<SaleHistoryResponse> result = asTenant(() -> writer.findHistory(businessId, from, to));

    assertThat(result).hasSize(1);
    SaleHistoryResponse response = result.get(0);
    assertThat(response.saleId()).isEqualTo(saleId);
    assertThat(response.orderId()).isEqualTo(orderId);
    assertThat(response.occurredAt()).isEqualTo(occurredAt);
    assertThat(response.amountMinor()).isEqualTo(150_000L);
    assertThat(response.currency()).isEqualTo("IDR");
    assertThat(response.tenderType()).isEqualTo("CASH");
    assertThat(response.channelCode()).isNull();
    verify(repository).findHistory(businessId, from, to);
  }

  @Test
  void aNullOrderIdIsPreservedForASaleWithNoLinkedOrder() {
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    Instant from = Instant.parse("2026-08-08T00:00:00Z");
    Instant to = Instant.parse("2026-08-09T00:00:00Z");

    SaleHistoryView view = mock(SaleHistoryView.class);
    when(view.getId()).thenReturn(UUID.randomUUID());
    when(view.getOrderId()).thenReturn(null);
    when(view.getOccurredAt()).thenReturn(Instant.parse("2026-08-08T03:00:00Z"));
    when(view.getAmountMinor()).thenReturn(50_000L);
    when(view.getCurrency()).thenReturn("IDR");
    when(view.getTenderType()).thenReturn(null);
    when(view.getChannelCode()).thenReturn(null);
    when(repository.findHistory(any(), any(), any())).thenReturn(List.of(view));

    List<SaleHistoryResponse> result = asTenant(() -> writer.findHistory(businessId, from, to));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).orderId()).isNull();
    assertThat(result.get(0).tenderType()).isNull();
  }

  @Test
  void anEmptyWindowReturnsAnEmptyList() {
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    Instant from = Instant.parse("2026-08-08T00:00:00Z");
    Instant to = Instant.parse("2026-08-09T00:00:00Z");
    when(repository.findHistory(businessId, from, to)).thenReturn(List.of());

    List<SaleHistoryResponse> result = asTenant(() -> writer.findHistory(businessId, from, to));

    assertThat(result).isEmpty();
  }
}
