package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
import id.co.nativeapp.finance.ar.service.InvoiceLineInput;
import id.co.nativeapp.finance.ar.service.InvoiceWriter;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.tax.dto.TaxFilingHistoryResponse;
import id.co.nativeapp.finance.tax.dto.TaxFilingResponse;
import id.co.nativeapp.finance.tax.dto.VatReturnResponse;
import id.co.nativeapp.finance.tax.service.FileReturnResult;
import id.co.nativeapp.finance.tax.service.TaxFilingReader;
import id.co.nativeapp.finance.tax.service.TaxFilingService;
import id.co.nativeapp.finance.tax.service.VatReturnReader;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end integration test (real PostgreSQL, unprivileged {@code app_user}) for the Phase 4 PPN
 * flow: seed a taxable AR invoice (output VAT) + a taxable AP bill (input VAT), read the VAT
 * return, FILE it (the netting entry clears the period's 2200/1300 and books the net to 2300),
 * verify the report then reports the sealed snapshot and a re-file is an idempotent no-op, SETTLE
 * it (clears 2300, credits CASH_CLEARING), and confirm RLS isolates the filing from another tenant.
 * The tax mirror of {@link BankTenancyIsolationTest}.
 */
@SpringBootTest
class TaxFilingTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-aaaaaaaaaa11";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-bbbbbbbbbb22";
  private static final String ACTOR = "tax@integration.co.id";

  @Autowired private CustomerWriter customerWriter;
  @Autowired private InvoiceWriter invoiceWriter;
  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private VatReturnReader vatReturnReader;
  @Autowired private TaxFilingService taxFilingService;
  @Autowired private TaxFilingReader taxFilingReader;
  @Autowired private GlTrialBalanceReader glTrialBalanceReader;
  @Autowired private Clock clock;

  @Test
  void fileAndSettleAPpnReturnEndToEnd() throws Exception {
    String period = LedgerPosting.periodOf(clock.instant());

    // Seed output VAT (AR invoice: 10,000,000 subtotal × 11% = 1,100,000) + input VAT (AP bill:
    // 4,000,000 subtotal × 11% = 440,000) for tenant A. Net = 660,000 PAYABLE.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          CustomerResponse customer = customerWriter.create("Acme Sales", null, null);
          UUID invoice =
              invoiceWriter.createDraft(
                  customer.id(),
                  "IDR",
                  true,
                  List.of(new InvoiceLineInput("Sale", 1, 10_000_000L)));
          invoiceWriter.issue(invoice, 30);
          VendorResponse vendor = vendorWriter.create("Acme Supplies", null, null);
          UUID bill =
              billWriter.createDraft(
                  vendor.id(),
                  "IDR",
                  true,
                  List.of(new BillLineInput("Purchase", 1, 4_000_000L, false)));
          billWriter.post(bill, 30);
          return null;
        });

    // The VAT report: output − input = net payable.
    VatReturnResponse report =
        TenantContext.callAs(TENANT_A, ACTOR, () -> vatReturnReader.read(period));
    assertThat(report.outputVatMinor()).isEqualTo(1_100_000L);
    assertThat(report.inputVatMinor()).isEqualTo(440_000L);
    assertThat(report.netMinor()).isEqualTo(660_000L);
    assertThat(report.netDirection()).isEqualTo("PAYABLE");
    assertThat(report.filed()).isFalse();

    // File the return.
    UUID filingId =
        TenantContext.callAs(TENANT_A, ACTOR, () -> taxFilingService.file(period).filingId());

    // The netting entry cleared the period's output/input VAT to zero and booked the net to 2300.
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(creditNet(period, "2200")).as("output VAT cleared").isZero();
          assertThat(creditNet(period, "1300")).as("input VAT cleared").isZero();
          assertThat(creditNet(period, "2300")).as("net VAT payable").isEqualTo(660_000L);
          return null;
        });

    // The report now reports the FILED snapshot (not the now-cleared live GL).
    VatReturnResponse filed =
        TenantContext.callAs(TENANT_A, ACTOR, () -> vatReturnReader.read(period));
    assertThat(filed.filed()).isTrue();
    assertThat(filed.outputVatMinor()).isEqualTo(1_100_000L);
    assertThat(filed.netMinor()).isEqualTo(660_000L);
    assertThat(filed.filingStatus()).isEqualTo("FILED");
    assertThat(filed.filingId()).isEqualTo(filingId);

    // A re-file is an idempotent no-op (same filing, created == false, nothing re-posted).
    FileReturnResult refile =
        TenantContext.callAs(TENANT_A, ACTOR, () -> taxFilingService.file(period));
    assertThat(refile.filingId()).isEqualTo(filingId);
    assertThat(refile.created()).isFalse();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(creditNet(period, "2300"))
              .as("re-file must not double-post the net")
              .isEqualTo(660_000L);
          return null;
        });

    // Settle: pay the net to the tax authority (Dr 2300 / Cr 1900).
    TenantContext.callAs(TENANT_A, ACTOR, () -> taxFilingService.settle(filingId));
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () -> {
          assertThat(creditNet(period, "2300")).as("VAT payable settled").isZero();
          assertThat(creditNet(period, "1900"))
              .as("payment routed through cash-clearing")
              .isEqualTo(660_000L);
          return null;
        });
    TaxFilingResponse detail =
        TenantContext.callAs(TENANT_A, ACTOR, () -> taxFilingReader.detail(filingId));
    assertThat(detail.status()).isEqualTo("SETTLED");
    assertThat(detail.settlementEntryId()).isNotNull();
    assertThat(detail.settledAt()).isNotNull();

    // RLS: tenant B sees neither the filing nor tenant A's history.
    VatReturnResponse bReport =
        TenantContext.callAs(TENANT_B, ACTOR, () -> vatReturnReader.read(period));
    assertThat(bReport.filed()).isFalse();
    TaxFilingHistoryResponse bHistory =
        TenantContext.callAs(TENANT_B, ACTOR, () -> taxFilingReader.history());
    assertThat(bHistory.filings()).isEmpty();
    TaxFilingHistoryResponse aHistory =
        TenantContext.callAs(TENANT_A, ACTOR, () -> taxFilingReader.history());
    assertThat(aHistory.filings()).hasSize(1);
    assertThat(aHistory.filings().getFirst().status()).isEqualTo("SETTLED");
  }

  /**
   * The credit-net ({@code Σcredit − Σdebit}) of {@code accountCode} in {@code period}'s GL trial
   * balance (0 when the account has no activity). Runs inside the caller's tenant scope (RLS).
   */
  private long creditNet(String period, String accountCode) {
    return glTrialBalanceReader.read(period).stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .map(l -> l.getTotalCreditMinor() - l.getTotalDebitMinor())
        .orElse(0L);
  }
}
