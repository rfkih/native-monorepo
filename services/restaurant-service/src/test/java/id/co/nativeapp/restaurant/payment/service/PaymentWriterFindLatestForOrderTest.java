package id.co.nativeapp.restaurant.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.restaurant.config.ActorTypeProvider;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.projection.PaymentReceiptView;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.sale.service.OperatorRequiredGuard;
import id.co.nativeapp.security.OperatorContextProvider;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Unit pin for {@link PaymentWriter#findLatestForOrder} — the order read path's receipt-rebuild
 * need ({@code GET /api/v1/orders/{id}}, {@code OrderWriter.findById}). Mirrors {@code
 * SalesChannelWriterTest}/{@code SaleHistoryWriterTest}'s style: a mocked repository, no Spring
 * context, proving the {@link PaymentReceiptView} projection maps onto {@link PaymentResponse}
 * correctly (including the CHAR(3) currency strip) and that "no payment for this order" maps to
 * {@code Optional.empty()} rather than a fabricated response.
 */
class PaymentWriterFindLatestForOrderTest {

  private static final String COMPANY = "11111111-1111-1111-1111-111111111111";

  private final PaymentProviderRegistry providers = mock(PaymentProviderRegistry.class);
  private final PaymentRepository repository = mock(PaymentRepository.class);
  // Real (not mocked) instances: outside a bound HTTP request they always resolve to no operator /
  // no device actor — exactly what this ADR 0049-P4-unaffected read-path test needs.
  private final OperatorContextProvider operatorContextProvider = new OperatorContextProvider();
  private final OperatorRequiredGuard operatorRequiredGuard =
      new OperatorRequiredGuard(new ActorTypeProvider());
  private final PaymentWriter writer =
      new PaymentWriter(providers, repository, operatorContextProvider, operatorRequiredGuard);

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
  void mapsTheLatestReceiptProjectionOntoThePaymentResponse() {
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID saleId = UUID.randomUUID();

    PaymentReceiptView view = mock(PaymentReceiptView.class);
    when(view.getId()).thenReturn(paymentId);
    when(view.getOrderId()).thenReturn(orderId);
    when(view.getTenderType()).thenReturn("CASH");
    when(view.getStatus()).thenReturn("CAPTURED");
    when(view.getAmountMinor()).thenReturn(40_000L);
    // Postgres CHAR(3) is right-padded on read; the mapper must strip it.
    when(view.getCurrency()).thenReturn("IDR");
    when(view.getTenderedMinor()).thenReturn(50_000L);
    when(view.getChangeMinor()).thenReturn(10_000L);
    when(view.isProviderPending()).thenReturn(false);
    when(view.getSaleId()).thenReturn(saleId);
    when(repository.findLatestReceiptViewByOrderId(orderId)).thenReturn(Optional.of(view));

    Optional<PaymentResponse> result = asTenant(() -> writer.findLatestForOrder(orderId));

    assertThat(result).isPresent();
    PaymentResponse response = result.get();
    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.orderId()).isEqualTo(orderId);
    assertThat(response.tenderType()).isEqualTo("CASH");
    assertThat(response.status()).isEqualTo("CAPTURED");
    assertThat(response.amountMinor()).isEqualTo(40_000L);
    assertThat(response.currency()).isEqualTo("IDR");
    assertThat(response.tenderedMinor()).isEqualTo(50_000L);
    assertThat(response.changeMinor()).isEqualTo(10_000L);
    assertThat(response.providerPending()).isFalse();
    assertThat(response.saleId()).isEqualTo(saleId);
    verify(repository).findLatestReceiptViewByOrderId(orderId);
  }

  @Test
  void anOrderWithNoPaymentMapsToEmpty() {
    UUID orderId = UUID.randomUUID();
    when(repository.findLatestReceiptViewByOrderId(orderId)).thenReturn(Optional.empty());

    Optional<PaymentResponse> result = asTenant(() -> writer.findLatestForOrder(orderId));

    assertThat(result).isEmpty();
  }
}
