package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceReader;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.ar.service.PaymentWriter;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests (real PostgreSQL, unprivileged {@code app_user}) for the two code-review fixes
 * the green happy-path suite did not exercise: payment idempotency (C1) and the
 * single-base-currency guard on issue (M1).
 */
@SpringBootTest
class ArWriterIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-cccccccccccc";
  private static final String ACTOR = "ap@integration.co.id";

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private PaymentWriter paymentWriter;
  @Autowired private InvoiceReader invoiceReader;

  @Test
  void partialPaymentRetryWithSameIdempotencyKeyDoesNotDoublePost() throws Exception {
    // Issue a 1,000,000 IDR invoice.
    UUID invoiceId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              CustomerResponse customer = customerWriter.create("Acme", null, null);
              UUID id =
                  invoiceWriter.createDraft(
                      customer.id(),
                      "IDR",
                      false,
                      List.of(new InvoiceLineInput("Item", 1, 1_000_000L)));
              invoiceWriter.issue(id, 30);
              return id;
            });

    String idempotencyKey = "pay-attempt-abc-123";
    // Record a 400,000 partial payment, then RETRY the identical request with the same key (the
    // lost-response scenario). The retry must replay the first payment, not post a second one.
    TenantContext.callAs(
        TENANT, ACTOR, () -> paymentWriter.record(invoiceId, 400_000L, "CASH", idempotencyKey));
    TenantContext.callAs(
        TENANT, ACTOR, () -> paymentWriter.record(invoiceId, 400_000L, "CASH", idempotencyKey));

    InvoiceDetailResponse detail =
        TenantContext.callAs(TENANT, ACTOR, () -> invoiceReader.detail(invoiceId));
    assertThat(detail.paidMinor())
        .as("paid must be 400,000 — NOT double-posted to 800,000")
        .isEqualTo(400_000L);
    assertThat(detail.payments()).as("exactly one payment recorded").hasSize(1);
    assertThat(detail.status()).isEqualTo("PARTIALLY_PAID");
  }

  @Test
  void issuingANonBaseCurrencyInvoiceInAnEstablishedPeriodIsRejected() throws Exception {
    // Establish IDR in the period (issue an IDR invoice), then draft a USD invoice for the same
    // period; issuing the USD invoice must be rejected before it can put two currencies in the GL.
    UUID usdInvoiceId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              CustomerResponse customer = customerWriter.create("Acme", null, null);
              UUID idr =
                  invoiceWriter.createDraft(
                      customer.id(),
                      "IDR",
                      false,
                      List.of(new InvoiceLineInput("IDR item", 1, 1_000_000L)));
              invoiceWriter.issue(idr, 30);
              return invoiceWriter.createDraft(
                  customer.id(),
                  "USD",
                  false,
                  List.of(new InvoiceLineInput("USD item", 1, 10_000L)));
            });

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> invoiceWriter.issue(usdInvoiceId, 30)))
        .as("a USD invoice in an IDR-established period must be rejected (M1)")
        .isInstanceOf(MismatchedPostingCurrencyException.class);
  }
}
