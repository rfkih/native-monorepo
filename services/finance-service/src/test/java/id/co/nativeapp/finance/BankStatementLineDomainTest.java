package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.bank.domain.BankStatementLine;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.domain.ReconciliationStateException;
import id.co.nativeapp.finance.bank.domain.StatementLineStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link BankStatementLine} aggregate's state machine (pure domain — no
 * Spring/DB). Pins the UNRECONCILED → RECONCILED transition and its guard: no double-reconcile. The
 * mirror of {@link BillDomainTest}.
 */
class BankStatementLineDomainTest {

  private static final UUID BANK_ACCOUNT = UUID.randomUUID();

  private static BankStatementLine line(long amountMinor) {
    return BankStatementLine.of(
        BANK_ACCOUNT, LocalDate.parse("2026-06-14"), amountMinor, "IDR", "Deposit", "REF-1");
  }

  @Test
  void createdLineIsUnreconciled() {
    BankStatementLine line = line(1_000_000L);
    assertThat(line.getStatus()).isEqualTo(StatementLineStatus.UNRECONCILED);
    assertThat(line.getAmountMinor()).isEqualTo(1_000_000L);
    assertThat(line.getCurrency()).isEqualTo("IDR");
    assertThat(line.getReconciledCategory()).isNull();
    assertThat(line.getJournalEntryId()).isNull();
  }

  @Test
  void zeroAmountIsRejected() {
    assertThatThrownBy(() -> line(0L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reconcileTransitionsToReconciledAndStampsTheEntry() {
    BankStatementLine line = line(-25_000L);
    UUID entryId = UUID.randomUUID();

    line.reconcile(ReconciliationCategory.BANK_FEE, entryId);

    assertThat(line.getStatus()).isEqualTo(StatementLineStatus.RECONCILED);
    assertThat(line.getReconciledCategory()).isEqualTo(ReconciliationCategory.BANK_FEE);
    assertThat(line.getJournalEntryId()).isEqualTo(entryId);
  }

  @Test
  void cannotDoubleReconcile() {
    BankStatementLine line = line(1_000_000L);
    line.reconcile(ReconciliationCategory.CLEARING, UUID.randomUUID());

    assertThatThrownBy(() -> line.reconcile(ReconciliationCategory.CLEARING, UUID.randomUUID()))
        .isInstanceOf(ReconciliationStateException.class);
  }
}
