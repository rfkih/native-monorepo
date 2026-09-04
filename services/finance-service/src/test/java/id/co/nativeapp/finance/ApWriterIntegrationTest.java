package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillPaymentWriter;
import id.co.nativeapp.finance.ap.service.BillReader;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests (real PostgreSQL, unprivileged {@code app_user}) for the two mechanics the
 * green happy-path suite did not exercise: payment idempotency and the single-base-currency guard
 * on post — the AP mirror of {@link ArWriterIntegrationTest} (AR's code-review C1 / M1).
 */
@SpringBootTest
class ApWriterIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "ffffffff-ffff-ffff-ffff-ddddddddddd3";
  private static final String ACTOR = "ap@integration.co.id";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private BillPaymentWriter billPaymentWriter;
  @Autowired private BillReader billReader;

  @Test
  void partialPaymentRetryWithSameIdempotencyKeyDoesNotDoublePost() throws Exception {
    // Post a 1,000,000 IDR bill.
    UUID billId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme", null, null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      false,
                      List.of(new BillLineInput("Item", 1, 1_000_000L, false)));
              billWriter.post(id, 30);
              return id;
            });

    String idempotencyKey = "pay-attempt-abc-123";
    // Record a 400,000 partial payment, then RETRY the identical request with the same key (the
    // lost-response scenario). The retry must replay the first payment, not post a second one.
    TenantContext.callAs(
        TENANT, ACTOR, () -> billPaymentWriter.record(billId, 400_000L, "CASH", idempotencyKey));
    TenantContext.callAs(
        TENANT, ACTOR, () -> billPaymentWriter.record(billId, 400_000L, "CASH", idempotencyKey));

    BillDetailResponse detail =
        TenantContext.callAs(TENANT, ACTOR, () -> billReader.detail(billId));
    assertThat(detail.paidMinor())
        .as("paid must be 400,000 — NOT double-posted to 800,000")
        .isEqualTo(400_000L);
    assertThat(detail.payments()).as("exactly one payment recorded").hasSize(1);
    assertThat(detail.status()).isEqualTo("PARTIALLY_PAID");
  }

  @Test
  void postingANonBaseCurrencyBillInAnEstablishedPeriodIsRejected() throws Exception {
    // Establish IDR in the period (post an IDR bill), then draft a USD bill for the same period;
    // posting the USD bill must be rejected before it can put two currencies in the GL.
    UUID usdBillId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme", null, null);
              UUID idr =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      false,
                      List.of(new BillLineInput("IDR item", 1, 1_000_000L, false)));
              billWriter.post(idr, 30);
              return billWriter.createDraft(
                  vendor.id(),
                  "USD",
                  false,
                  List.of(new BillLineInput("USD item", 1, 10_000L, false)));
            });

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> billWriter.post(usdBillId, 30)))
        .as("a USD bill in an IDR-established period must be rejected (mirroring AR's M1)")
        .isInstanceOf(MismatchedPostingCurrencyException.class);
  }
}
