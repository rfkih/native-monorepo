package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ar.domain.InvoiceNotFoundException;
import id.co.nativeapp.finance.ar.dto.AgingResponse;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceDetailResponse;
import id.co.nativeapp.finance.ar.dto.InvoiceSummaryResponse;
import id.co.nativeapp.finance.ar.service.AgingReader;
import id.co.nativeapp.finance.ar.service.CustomerReader;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceReader;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.ar.service.PaymentWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end AR test against real PostgreSQL 16 as the unprivileged {@code app_user} — it both
 * drives the full writer → double-entry GL path (create customer → draft → issue → part-pay) and
 * proves cross-tenant isolation via AUTO-applied RLS: everything tenant A creates is invisible to
 * tenant B (empty lists; a detail read of A's invoice as B is a 404). The writers bind the tenant
 * from {@link TenantContext}; the reads carry NO {@code WHERE company_id} — only the RLS aspect
 * scopes them (rule 5). {@code FORCE ROW LEVEL SECURITY} on the AR tables binds even the owning
 * role.
 */
@SpringBootTest
class ArTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-aaaaaaaaaaaa";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-bbbbbbbbbbbb";
  private static final String ACTOR_A = "ap-a@isolation.co.id";
  private static final String ACTOR_B = "viewer-b@isolation.co.id";

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private PaymentWriter paymentWriter;
  @Autowired private CustomerReader customerReader;
  @Autowired private InvoiceReader invoiceReader;
  @Autowired private AgingReader agingReader;

  @Test
  void arDataUnderTenantAIsInvisibleToTenantB() throws Exception {
    // Tenant A: create a customer, draft a taxable invoice (2 × 500,000 IDR), issue it, part-pay
    // it.
    UUID invoiceId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              CustomerResponse customer = customerWriter.create("Acme A", "ap@acme.test", null);
              UUID id =
                  invoiceWriter.createDraft(
                      customer.id(),
                      "IDR",
                      true,
                      List.of(new InvoiceLineInput("Consulting", 2, 500_000L)));
              invoiceWriter.issue(id, 30);
              paymentWriter.record(id, 300_000L, "CASH", null);
              return id;
            });

    // Tenant A sees its own invoice with the illustrative 11% VAT and the partial payment applied.
    InvoiceDetailResponse aView =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> invoiceReader.detail(invoiceId));
    assertThat(aView.status()).isEqualTo("PARTIALLY_PAID");
    assertThat(aView.subtotalMinor()).isEqualTo(1_000_000L);
    assertThat(aView.taxMinor()).isEqualTo(110_000L); // 11% illustrative output VAT
    assertThat(aView.totalMinor()).isEqualTo(1_110_000L);
    assertThat(aView.paidMinor()).isEqualTo(300_000L);
    assertThat(aView.outstandingMinor()).isEqualTo(810_000L);
    assertThat(aView.usesIllustrativeRules()).isTrue();
    assertThat(aView.invoiceNumber()).isEqualTo("INV-00001");

    // Tenant A's aging shows the outstanding balance in the current bucket (due date is 30 days
    // out).
    AgingResponse aging = TenantContext.callAs(TENANT_A, ACTOR_A, () -> agingReader.aging(null));
    assertThat(aging.totals().outstandingMinor()).isEqualTo(810_000L);
    assertThat(aging.totals().currentMinor()).isEqualTo(810_000L);

    // Tenant B sees nothing: customer + invoice lists are empty, and a detail read of A's invoice
    // 404s.
    List<CustomerResponse> bCustomers =
        TenantContext.callAs(TENANT_B, ACTOR_B, customerReader::list);
    assertThat(bCustomers).as("tenant B must see none of A's customers (RLS)").isEmpty();

    List<InvoiceSummaryResponse> bInvoices =
        TenantContext.callAs(TENANT_B, ACTOR_B, () -> invoiceReader.list(null, null, null));
    assertThat(bInvoices).as("tenant B must see none of A's invoices (RLS)").isEmpty();

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR_B, () -> invoiceReader.detail(invoiceId)))
        .as("tenant B reading A's invoice detail is a 404 (RLS-invisible)")
        .isInstanceOf(InvoiceNotFoundException.class);
  }
}
