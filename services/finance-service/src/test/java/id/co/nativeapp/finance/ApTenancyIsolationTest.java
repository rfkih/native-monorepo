package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.dto.AgingResponse;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.dto.BillSummaryResponse;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.ApAgingReader;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillPaymentWriter;
import id.co.nativeapp.finance.ap.service.BillReader;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorReader;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end AP test against real PostgreSQL 16 as the unprivileged {@code app_user} — it both
 * drives the full writer → double-entry GL path (create vendor → draft → post → part-pay) and
 * proves cross-tenant isolation via AUTO-applied RLS: everything tenant A creates is invisible to
 * tenant B (empty lists; a detail read of A's bill as B is a 404). The writers bind the tenant from
 * {@link TenantContext}; the reads carry NO {@code WHERE company_id} — only the RLS aspect scopes
 * them (rule 5). {@code FORCE ROW LEVEL SECURITY} on the AP tables binds even the owning role. The
 * mirror of {@link ArTenancyIsolationTest}.
 */
@SpringBootTest
class ApTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-ddddddddddd1";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-ddddddddddd2";
  private static final String ACTOR_A = "ap-a@isolation.co.id";
  private static final String ACTOR_B = "viewer-b@isolation.co.id";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private BillPaymentWriter billPaymentWriter;
  @Autowired private VendorReader vendorReader;
  @Autowired private BillReader billReader;
  @Autowired private ApAgingReader apAgingReader;

  @Test
  void apDataUnderTenantAIsInvisibleToTenantB() throws Exception {
    // Tenant A: create a vendor, draft a taxable bill (2 × 500,000 IDR), post it, part-pay it.
    UUID billId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme A", "ap@acme.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(new BillLineInput("Supplies", 2, 500_000L, false)));
              billWriter.post(id, 30);
              billPaymentWriter.record(id, 300_000L, "CASH", null);
              return id;
            });

    // Tenant A sees its own bill with the illustrative 11% input VAT and the partial payment
    // applied.
    BillDetailResponse aView =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> billReader.detail(billId));
    assertThat(aView.status()).isEqualTo("PARTIALLY_PAID");
    assertThat(aView.subtotalMinor()).isEqualTo(1_000_000L);
    assertThat(aView.taxMinor()).isEqualTo(110_000L); // 11% official input VAT (PPN)
    assertThat(aView.totalMinor()).isEqualTo(1_110_000L);
    assertThat(aView.paidMinor()).isEqualTo(300_000L);
    assertThat(aView.outstandingMinor()).isEqualTo(810_000L);
    assertThat(aView.usesIllustrativeRules()).isFalse(); // VAT is now official 11% (ADR 0042)
    assertThat(aView.billNumber()).isEqualTo("BILL-00001");

    // Tenant A's aging shows the outstanding balance in the current bucket (due date is 30 days
    // out).
    AgingResponse aging = TenantContext.callAs(TENANT_A, ACTOR_A, () -> apAgingReader.aging(null));
    assertThat(aging.totals().outstandingMinor()).isEqualTo(810_000L);
    assertThat(aging.totals().currentMinor()).isEqualTo(810_000L);

    // Tenant B sees nothing: vendor + bill lists are empty, and a detail read of A's bill 404s.
    List<VendorResponse> bVendors = TenantContext.callAs(TENANT_B, ACTOR_B, vendorReader::list);
    assertThat(bVendors).as("tenant B must see none of A's vendors (RLS)").isEmpty();

    List<BillSummaryResponse> bBills =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> billReader.list(null, null, null));
    assertThat(bBills).as("tenant B must see none of A's bills (RLS)").isEmpty();

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR_B, () -> billReader.detail(billId)))
        .as("tenant B reading A's bill detail is a 404 (RLS-invisible)")
        .isInstanceOf(BillNotFoundException.class);
  }
}
