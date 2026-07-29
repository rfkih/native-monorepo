package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ap.domain.Bill;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.domain.BillStatus;
import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Bill} aggregate's state machine (pure domain — no Spring/DB). Pins the
 * DRAFT → POSTED → (PARTIALLY_)PAID / VOID transitions and their guards: no double-post, no
 * overpay, no paying a draft, no voiding a paid bill. The mirror of {@link InvoiceDomainTest}.
 */
class BillDomainTest {

  private static final UUID VENDOR = UUID.randomUUID();

  private static Money idr(long minor) {
    return Money.ofMinor(minor, "IDR");
  }

  private Bill draft(long subtotal, long tax) {
    return Bill.draft(VENDOR, idr(subtotal), idr(tax), tax > 0);
  }

  private Bill posted(long subtotal, long tax) {
    Bill bill = draft(subtotal, tax);
    bill.post(
        "BILL-00001",
        LocalDate.parse("2026-06-14"),
        LocalDate.parse("2026-07-14"),
        UUID.randomUUID());
    return bill;
  }

  @Test
  void draftComputesTotalsAndFlagsTax() {
    Bill bill = draft(1_000_000L, 110_000L);
    assertThat(bill.getStatus()).isEqualTo(BillStatus.DRAFT);
    assertThat(bill.total()).isEqualTo(idr(1_110_000L));
    assertThat(bill.outstanding()).isEqualTo(idr(1_110_000L));
    assertThat(bill.isUsesIllustrativeRules()).isTrue();
  }

  @Test
  void cannotPostTwice() {
    Bill bill = posted(1_000_000L, 0L);
    assertThat(bill.getStatus()).isEqualTo(BillStatus.POSTED);
    assertThatThrownBy(
            () -> bill.post("BILL-2", LocalDate.now(), LocalDate.now(), UUID.randomUUID()))
        .isInstanceOf(BillStateException.class);
  }

  @Test
  void partialThenFullPaymentTransitionsStatus() {
    Bill bill = posted(1_000_000L, 0L);
    bill.recordPayment(idr(400_000L));
    assertThat(bill.getStatus()).isEqualTo(BillStatus.PARTIALLY_PAID);
    assertThat(bill.outstanding()).isEqualTo(idr(600_000L));
    bill.recordPayment(idr(600_000L));
    assertThat(bill.getStatus()).isEqualTo(BillStatus.PAID);
    assertThat(bill.outstanding()).isEqualTo(idr(0L));
  }

  @Test
  void cannotOverpay() {
    Bill bill = posted(1_000_000L, 0L);
    assertThatThrownBy(() -> bill.recordPayment(idr(1_000_001L)))
        .isInstanceOf(BillStateException.class);
  }

  @Test
  void cannotPayADraft() {
    Bill bill = draft(1_000_000L, 0L);
    assertThatThrownBy(() -> bill.recordPayment(idr(100L))).isInstanceOf(BillStateException.class);
  }

  @Test
  void cannotVoidAPaidBill() {
    Bill bill = posted(1_000_000L, 0L);
    bill.recordPayment(idr(1_000_000L));
    assertThatThrownBy(bill::voidBill).isInstanceOf(BillStateException.class);
  }

  @Test
  void canVoidAnUnpaidPostedBill() {
    Bill bill = posted(1_000_000L, 0L);
    bill.voidBill();
    assertThat(bill.getStatus()).isEqualTo(BillStatus.VOID);
  }

  @Test
  void canVoidADraft() {
    Bill bill = draft(1_000_000L, 0L);
    bill.voidBill();
    assertThat(bill.getStatus()).isEqualTo(BillStatus.VOID);
  }
}
