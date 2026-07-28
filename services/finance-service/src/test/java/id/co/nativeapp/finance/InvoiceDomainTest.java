package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ar.domain.Invoice;
import id.co.nativeapp.finance.ar.domain.InvoiceStateException;
import id.co.nativeapp.finance.ar.domain.InvoiceStatus;
import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Invoice} aggregate's state machine (pure domain — no Spring/DB). Pins
 * the DRAFT → ISSUED → (PARTIALLY_)PAID / VOID transitions and their guards: no double-issue, no
 * overpay, no paying a draft, no voiding a paid invoice.
 */
class InvoiceDomainTest {

  private static final UUID CUSTOMER = UUID.randomUUID();

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  private Invoice draft(long subtotal, long tax) {
    return Invoice.draft(CUSTOMER, idr(subtotal), idr(tax), tax > 0);
  }

  private Invoice issued(long subtotal, long tax) {
    Invoice invoice = draft(subtotal, tax);
    invoice.issue(
        "INV-00001",
        LocalDate.parse("2026-06-14"),
        LocalDate.parse("2026-07-14"),
        UUID.randomUUID());
    return invoice;
  }

  @Test
  void draftComputesTotalsAndFlagsTax() {
    Invoice invoice = draft(1_000_000L, 110_000L);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    assertThat(invoice.total()).isEqualTo(idr(1_110_000L));
    assertThat(invoice.outstanding()).isEqualTo(idr(1_110_000L));
    assertThat(invoice.isUsesIllustrativeRules()).isTrue();
  }

  @Test
  void cannotIssueTwice() {
    Invoice invoice = issued(1_000_000L, 0L);
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    assertThatThrownBy(
            () -> invoice.issue("INV-2", LocalDate.now(), LocalDate.now(), UUID.randomUUID()))
        .isInstanceOf(InvoiceStateException.class);
  }

  @Test
  void partialThenFullPaymentTransitionsStatus() {
    Invoice invoice = issued(1_000_000L, 0L);
    invoice.recordPayment(idr(400_000L));
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    assertThat(invoice.outstanding()).isEqualTo(idr(600_000L));
    invoice.recordPayment(idr(600_000L));
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    assertThat(invoice.outstanding()).isEqualTo(idr(0L));
  }

  @Test
  void cannotOverpay() {
    Invoice invoice = issued(1_000_000L, 0L);
    assertThatThrownBy(() -> invoice.recordPayment(idr(1_000_001L)))
        .isInstanceOf(InvoiceStateException.class);
  }

  @Test
  void cannotPayADraft() {
    Invoice invoice = draft(1_000_000L, 0L);
    assertThatThrownBy(() -> invoice.recordPayment(idr(100L)))
        .isInstanceOf(InvoiceStateException.class);
  }

  @Test
  void cannotVoidAPaidInvoice() {
    Invoice invoice = issued(1_000_000L, 0L);
    invoice.recordPayment(idr(1_000_000L));
    assertThatThrownBy(invoice::voidInvoice).isInstanceOf(InvoiceStateException.class);
  }

  @Test
  void canVoidAnUnpaidIssuedInvoice() {
    Invoice invoice = issued(1_000_000L, 0L);
    invoice.voidInvoice();
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VOID);
  }

  @Test
  void canVoidADraft() {
    Invoice invoice = draft(1_000_000L, 0L);
    invoice.voidInvoice();
    assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VOID);
  }
}
