package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.finance.reversal.service.ReversalPostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The B1 ONLINE wiring end-to-end (ADR 0036 review W1) — the exact path the deploy-order rule
 * exists for: an ONLINE {@code SaleRecorded} must debit PLATFORM_RECEIVABLE (1250), never drawer
 * cash (1900), and must accrue the per-channel {@code platform_receivable} sub-ledger by the GROSS
 * amount in the same transaction; a subsequent void must credit 1250 per-leg and claw the
 * accumulator back to zero. Also pins the null-channel UNKNOWN bucket (money is never dropped).
 */
@SpringBootTest
class OnlineSaleFlowTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-dddddddddddd";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-dddddddddddd");
  private static final Instant OCCURRED = Instant.parse("2026-07-20T10:00:00Z");

  @Autowired private RevenuePostingService revenueService;
  @Autowired private ReversalPostingService reversalService;

  private static SaleRecordedEvent onlineSale(UUID eventId, UUID saleId, long minor, String chan) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        OUTLET,
        Money.ofMinor(minor, "IDR"),
        OCCURRED,
        "ONLINE",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        chan);
  }

  @Test
  void onlineSaleDebitsPlatformReceivableAndAccruesTheChannelSubLedger() throws Exception {
    UUID saleId = UUID.randomUUID();
    revenueService.handle(onlineSale(UUID.randomUUID(), saleId, 52_000L, "GOFOOD"));

    // The SALE entry's clearing debit lands on 1250 — never 1900 (drawer cash).
    assertThat(debitOnAccountAsAdmin("1250")).isEqualTo(52_000L);
    assertThat(debitOnAccountAsAdmin("1900")).isZero();
    // The sub-ledger accrued the GROSS under the channel.
    assertThat(outstandingAsAdmin("GOFOOD")).isEqualTo(52_000L);
  }

  @Test
  void onlineVoidCreditsPlatformReceivableAndClawsTheSubLedgerBack() throws Exception {
    UUID saleId = UUID.randomUUID();
    revenueService.handle(onlineSale(UUID.randomUUID(), saleId, 30_000L, "GOFOOD"));
    assertThat(outstandingAsAdmin("GOFOOD")).isEqualTo(30_000L);

    reversalService.handleVoid(
        new SaleVoidedEvent(
            UUID.randomUUID(),
            TENANT,
            OUTLET,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(30_000L, "IDR"),
            OCCURRED.plusSeconds(600),
            "ONLINE",
            "GOFOOD"));

    // Per-leg unwind: the contra credits 1250 by the same amount, and the sub-ledger nets to 0.
    assertThat(creditOnAccountAsAdmin("1250")).isEqualTo(30_000L);
    assertThat(outstandingAsAdmin("GOFOOD")).isZero();
  }

  @Test
  void onlineSaleWithNullChannelAccruesUnderUnknownNeverDropped() throws Exception {
    revenueService.handle(onlineSale(UUID.randomUUID(), UUID.randomUUID(), 10_000L, null));

    assertThat(outstandingAsAdmin("UNKNOWN")).isEqualTo(10_000L);
    assertThat(debitOnAccountAsAdmin("1250")).isEqualTo(10_000L);
  }

  private long debitOnAccountAsAdmin(String accountCode) throws Exception {
    return sumAsAdmin("SELECT COALESCE(SUM(debit_minor), 0) FROM journal_line WHERE account_code = ?", accountCode);
  }

  private long creditOnAccountAsAdmin(String accountCode) throws Exception {
    return sumAsAdmin(
        "SELECT COALESCE(SUM(credit_minor), 0) FROM journal_line WHERE account_code = ?",
        accountCode);
  }

  private long outstandingAsAdmin(String channel) throws Exception {
    return sumAsAdmin(
        "SELECT COALESCE(SUM(outstanding_minor), 0) FROM platform_receivable WHERE channel_code"
            + " = ?",
        channel);
  }

  private long sumAsAdmin(String sql, String arg) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
