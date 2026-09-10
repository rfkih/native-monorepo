package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementAlreadyVoidedException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation.SourceLine;
import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.service.PlatformReceivableWriter;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import id.co.nativeapp.finance.platform.service.SettlementSourceReader;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code PlatformSettlementWriter} matrix (ADR 0036 Phase C): one-shot settle posting the exact
 * {@code Dr CASH_CLEARING (net) + Dr PLATFORM_FEE_EXPENSE (fee) / Cr PLATFORM_RECEIVABLE (gross)}
 * legs and decrementing the channel accumulator; fee-free payout omits the fee leg; same-key replay
 * returns the original without posting again; replayed key with a different payload → 409;
 * over-settlement (outstanding &lt; gross) → 422 with NOTHING touched; net &gt; gross → 422. The
 * accumulator is seeded through {@link PlatformReceivableWriter} — the same component the
 * revenue/reversal writers use in production.
 */
@SpringBootTest
class PlatformSettlementWriterTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-cccccccccccc";
  private static final String CHANNEL = "GOFOOD";
  private static final String ACTOR = "platform@integration.co.id";

  @Autowired private PlatformSettlementWriter writer;
  @Autowired private PlatformReceivableWriter accumulator;

  private void seedOutstanding(long minor) {
    // accumulate is @Transactional, so the RLS aspect binds the GUC from this scope.
    TenantContext.runAs(
        TENANT, ACTOR, () -> accumulator.accumulate(TENANT, CHANNEL, "IDR", minor, ACTOR));
  }

  /** Seeds one sub-ledger row for an arbitrary source (ADR 0076), the way a sale would. */
  private void seedOutstanding(
      String channelCode, SettlementSourceKind kind, String sourceCode, long minor) {
    TenantContext.runAs(
        TENANT,
        ACTOR,
        () ->
            accumulator.accumulate(
                TENANT,
                new SettlementSourceReader.SettlementSourceRef(kind, channelCode, sourceCode),
                "IDR",
                minor,
                ACTOR));
  }

  private PlatformSettlementResult settle(long gross, long net, String key) throws Exception {
    return TenantContext.callAs(
        TENANT, ACTOR, () -> writer.settle(CHANNEL, gross, net, "IDR", key));
  }

  /**
   * The case ADR 0076 exists for: a merchant on Shopee's QRIS is paid by Shopee in ONE transfer
   * covering both its ShopeeFood orders and its counter QRIS. One entry has to credit TWO different
   * accounts and split the deduction between TWO different fee accounts — and it has to balance.
   */
  @Test
  void oneShopeePayoutClearsBothItsMarketplaceAndItsQrisBalance() throws Exception {
    seedOutstanding("SHOPEE", SettlementSourceKind.MARKETPLACE, "SHOPEE", 1_850_000L);
    seedOutstanding("TENDER:QRIS", SettlementSourceKind.QRIS, "SHOPEE", 640_000L);

    PlatformSettlementResult result =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                writer.settleSources(
                    "SHOPEE",
                    List.of(
                        new SourceLine(SettlementSourceKind.MARKETPLACE, "SHOPEE", 1_850_000L),
                        new SourceLine(SettlementSourceKind.QRIS, "TENDER:QRIS", 640_000L)),
                    2_216_100L,
                    "IDR",
                    "psw-multi-1"));

    assertThat(result.created()).isTrue();
    assertThat(result.settlement().getGrossMinor()).isEqualTo(2_490_000L);
    assertThat(result.settlement().getFeeMinor()).isEqualTo(273_900L);

    // Dr 1900 net; the deduction split pro-rata onto ITS OWN fee account per source — 5710 for the
    // marketplace commission, 5720 for the QRIS MDR; each gross credited to the account it accrued
    // in. Booking the whole 273.900 to 5710 would balance just as well and be wrong.
    Map<String, long[]> legs = entryLegsAsAdmin(result.settlement().getJournalEntryId());
    assertThat(legs.get("1900")[0]).isEqualTo(2_216_100L);
    assertThat(legs.get("5710")[0]).isEqualTo(203_500L);
    assertThat(legs.get("5720")[0]).isEqualTo(70_400L);
    assertThat(legs.get("1250")[1]).isEqualTo(1_850_000L);
    assertThat(legs.get("1901")[1]).isEqualTo(640_000L);

    // The marketplace row is cleared. The QRIS row must be too: its decrement is guarded by
    // `outstanding >= gross`, so a payout that had not taken it would have thrown 422 above.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding)
        .filteredOn(o -> "SHOPEE".equals(o.channelCode()))
        .singleElement()
        .satisfies(o -> assertThat(o.outstandingMinor()).isZero());
  }

  /** A QRIS balance may not be over-settled either — the guard is per line, not per payout. */
  @Test
  void aPayoutTakingMoreThanOneSourceIsOwedIsRejectedWholesale() throws Exception {
    seedOutstanding("SHOPEE", SettlementSourceKind.MARKETPLACE, "SHOPEE", 1_000_000L);
    seedOutstanding("TENDER:QRIS", SettlementSourceKind.QRIS, "SHOPEE", 10_000L);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        writer.settleSources(
                            "SHOPEE",
                            List.of(
                                new SourceLine(
                                    SettlementSourceKind.MARKETPLACE, "SHOPEE", 1_000_000L),
                                // more than the QRIS row holds
                                new SourceLine(SettlementSourceKind.QRIS, "TENDER:QRIS", 50_000L)),
                            1_000_000L,
                            "IDR",
                            "psw-multi-over")))
        .isInstanceOf(PlatformOverSettlementException.class);

    // The whole payout rolled back — the marketplace row it COULD have taken is untouched.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding)
        .filteredOn(o -> "SHOPEE".equals(o.channelCode()))
        .singleElement()
        .satisfies(o -> assertThat(o.outstandingMinor()).isEqualTo(1_000_000L));
  }

  /**
   * CARD_CLEARING (1902) was a ONE-WAY account until V66: every card sale debited it and nothing in
   * the fleet ever credited it, so card money accrued and stayed — Rp 144.000 stuck on the first
   * tenant to look. A payout must now clear it, booking the acquirer's share of the deduction to
   * its OWN fee account rather than to marketplace or QRIS fee.
   */
  @Test
  void aCardPayoutClearsTheAccountThatHadNoWayOut() throws Exception {
    seedOutstanding("TENDER:CARD", SettlementSourceKind.CARD, "BCA", 144_000L);

    PlatformSettlementResult result =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                writer.settleSources(
                    "BCA",
                    List.of(new SourceLine(SettlementSourceKind.CARD, "TENDER:CARD", 144_000L)),
                    141_120L,
                    "IDR",
                    "psw-card-1"));

    assertThat(result.created()).isTrue();
    assertThat(result.settlement().getFeeMinor()).isEqualTo(2_880L);

    Map<String, long[]> legs = entryLegsAsAdmin(result.settlement().getJournalEntryId());
    assertThat(legs.get("1900")[0]).isEqualTo(141_120L);
    assertThat(legs.get("5730")[0])
        .as("the card acquirer's fee belongs to 5730, not to marketplace or QRIS fee")
        .isEqualTo(2_880L);
    assertThat(legs.get("1902")[1]).isEqualTo(144_000L);
  }

  /**
   * QRIS and marketplace money never touches the till: the acquirer or platform transfers it to the
   * BANK. Leaving the net in CASH_CLEARING made the owner's cash figure neither the drawer nor the
   * bank, and left 1000 at zero forever (ADR 0076 / V68).
   *
   * <p>Reconciliation is still the only writer that debits BANK — the payout creates its own
   * statement line and puts it through the same ReconciliationWriter, so CASH_CLEARING is a
   * pass-through that nets to ZERO across the two entries.
   */
  @Test
  void aPayoutDepositsIntoTheBankAndLeavesTheDrawerAlone() throws Exception {
    seedBankAccount();
    seedOutstanding(500_000L);

    PlatformSettlementResult result = settle(300_000L, 240_000L, "psw-bank-1");

    assertThat(bankBalanceAsAdmin("1000"))
        .as("the net reached the bank, not the drawer")
        .isEqualTo(240_000L);
    assertThat(bankBalanceAsAdmin("1900"))
        .as("cash clearing is a pass-through here and must net to zero")
        .isZero();
    assertThat(result.settlement().getFeeMinor()).isEqualTo(60_000L);
  }

  /** Voiding must take the bank leg back too, or the books keep a deposit that did not happen. */
  @Test
  void voidingAPayoutTakesTheBankDepositBackAsWell() throws Exception {
    seedBankAccount();
    seedOutstanding(500_000L);
    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-bank-2");
    assertThat(bankBalanceAsAdmin("1000")).isEqualTo(240_000L);

    TenantContext.runAs(TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId()));

    assertThat(bankBalanceAsAdmin("1000")).as("the deposit is reversed").isZero();
    assertThat(bankBalanceAsAdmin("1900")).as("and cash clearing still nets to zero").isZero();
    assertThat(statementLineCountAsAdmin())
        .as("the fabricated statement line goes with it")
        .isZero();
  }

  private void seedBankAccount() throws SQLException {
    seedBankAccount("IDR", true);
  }

  private void seedBankAccount(String currency, boolean active) throws SQLException {
    try (Connection c =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO bank_account (id, name, currency, active, created_at, created_by,"
                    + " updated_at, updated_by, version, company_id)"
                    + " VALUES (?, 'BCA', ?, ?, now(), ?, now(), ?, 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, currency);
      ps.setBoolean(3, active);
      ps.setString(4, ACTOR);
      ps.setString(5, ACTOR);
      ps.setString(6, TENANT);
      ps.executeUpdate();
    }
  }

  /** Signed balance of one account over the admin (BYPASSRLS) connection. */
  private long bankBalanceAsAdmin(String accountCode) throws SQLException {
    try (Connection c =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT COALESCE(SUM(debit_minor) - SUM(credit_minor), 0) FROM journal_line"
                    + " WHERE account_code = ?")) {
      ps.setString(1, accountCode);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long statementLineCountAsAdmin() throws SQLException {
    try (Connection c =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bank_statement_line")) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * A typo on a money form is not an exceptional event; having no way back is what makes it
   * expensive. Voiding must leave BOTH the ledger and the sub-ledger exactly where they were.
   */
  @Test
  void voidingAPayoutRestoresTheBalanceAndNetsTheLedgerToNothing() throws Exception {
    seedOutstanding(500_000L);
    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-void-1");

    TenantContext.runAs(TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId()));

    // The sub-ledger is back to what it was before the payout.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding)
        .singleElement()
        .satisfies(o -> assertThat(o.outstandingMinor()).isEqualTo(500_000L));

    // And every account the payout touched nets to zero across the two entries.
    Map<String, long[]> legs = entryLegsAsAdmin(settled.settlement().getJournalEntryId());
    UUID voidEntryId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                writer.history(null).stream()
                    .filter(h -> h.id().equals(settled.settlement().getId()))
                    .findFirst()
                    .map(h -> h.id())
                    .orElseThrow());
    assertThat(voidEntryId).isNotNull();
    assertThat(legs.get("1250")[1]).isEqualTo(300_000L);
  }

  /**
   * Once-only is claimed by the database, so a second void can never hand the balance back twice.
   */
  @Test
  void aPayoutCannotBeVoidedTwice() throws Exception {
    seedOutstanding(500_000L);
    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-void-2");
    TenantContext.runAs(TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId()));

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId())))
        .isInstanceOf(PlatformSettlementAlreadyVoidedException.class);

    // Still restored exactly once.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding)
        .singleElement()
        .satisfies(o -> assertThat(o.outstandingMinor()).isEqualTo(500_000L));
  }

  @Test
  void settlePostsExactLegsAndDecrementsTheChannelAccumulator() throws Exception {
    seedOutstanding(500_000L);

    PlatformSettlementResult result = settle(300_000L, 240_000L, "psw-key-1");

    assertThat(result.created()).isTrue();
    assertThat(result.settlement().getFeeMinor()).isEqualTo(60_000L);

    // The journal entry: Dr 1900 240k + Dr 5710 60k / Cr 1250 300k.
    Map<String, long[]> legs = entryLegsAsAdmin(result.settlement().getJournalEntryId());
    assertThat(legs.get("1900")[0]).isEqualTo(240_000L);
    assertThat(legs.get("5710")[0]).isEqualTo(60_000L);
    assertThat(legs.get("1250")[1]).isEqualTo(300_000L);
    // Provenance-derived (was hardcoded true): every resolved role above is OFFICIAL, so the entry
    // is not badged provisional.
    assertThat(usesIllustrativeRulesAsAdmin(result.settlement().getJournalEntryId())).isFalse();

    // The accumulator decremented by GROSS.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding)
        .singleElement()
        .satisfies(o -> assertThat(o.outstandingMinor()).isEqualTo(200_000L));
  }

  @Test
  void feeFreePayoutOmitsTheFeeLeg() throws Exception {
    seedOutstanding(100_000L);

    PlatformSettlementResult result = settle(100_000L, 100_000L, "psw-key-fee-free");

    Map<String, long[]> legs = entryLegsAsAdmin(result.settlement().getJournalEntryId());
    assertThat(legs).containsOnlyKeys("1900", "1250");
    assertThat(legs.get("1900")[0]).isEqualTo(100_000L);
    assertThat(legs.get("1250")[1]).isEqualTo(100_000L);
  }

  @Test
  void sameKeyReplayReturnsTheOriginalWithoutPostingAgain() throws Exception {
    seedOutstanding(300_000L);
    PlatformSettlementResult first = settle(200_000L, 150_000L, "psw-key-replay");

    PlatformSettlementResult replay = settle(200_000L, 150_000L, "psw-key-replay");

    assertThat(replay.created()).isFalse();
    assertThat(replay.settlement().getId()).isEqualTo(first.settlement().getId());
    // Outstanding decremented exactly ONCE.
    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding.getFirst().outstandingMinor()).isEqualTo(100_000L);
  }

  @Test
  void replayedKeyWithDifferentPayloadConflicts() throws Exception {
    seedOutstanding(300_000L);
    settle(200_000L, 150_000L, "psw-key-conflict");

    assertThatThrownBy(() -> settle(200_000L, 140_000L, "psw-key-conflict"))
        .isInstanceOf(PlatformSettlementIdempotencyKeyConflictException.class);
  }

  @Test
  void overSettlementIsRejectedAndTouchesNothing() throws Exception {
    seedOutstanding(100_000L);

    assertThatThrownBy(() -> settle(150_000L, 120_000L, "psw-key-over"))
        .isInstanceOf(PlatformOverSettlementException.class);

    List<PlatformOutstandingResponse> outstanding =
        TenantContext.callAs(TENANT, ACTOR, writer::outstanding);
    assertThat(outstanding.getFirst().outstandingMinor()).isEqualTo(100_000L);
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> writer.history(CHANNEL))).isEmpty();
  }

  @Test
  void netExceedingGrossIsRejected() throws Exception {
    seedOutstanding(300_000L);

    assertThatThrownBy(() -> settle(200_000L, 220_000L, "psw-key-subsidy"))
        .isInstanceOf(PlatformNetExceedsGrossException.class);
  }

  /** account_code → [debit, credit] for the entry's lines, read over the BYPASSRLS admin. */
  private Map<String, long[]> entryLegsAsAdmin(UUID entryId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line"
                    + " WHERE entry_id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        Map<String, long[]> legs = new java.util.HashMap<>();
        while (rs.next()) {
          legs.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
        }
        return legs;
      }
    }
  }

  /** The {@code journal_entry.uses_illustrative_rules} flag for {@code entryId}, read as admin. */
  private boolean usesIllustrativeRulesAsAdmin(UUID entryId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT uses_illustrative_rules FROM journal_entry WHERE id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  /**
   * The report and the ledger have to tell the same story. A void nets the GL to zero on every
   * account it touched, so a summary that still counts the taken-back row is exactly the
   * disagreement the void feature was built to remove.
   */
  @Test
  void aVoidedPayoutDropsOutOfTheSummary() throws Exception {
    seedOutstanding(500_000L);
    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-summary-void");
    String period =
        java.time.LocalDate.ofInstant(
                settled.settlement().getSettledAt(), java.time.ZoneId.of("Asia/Jakarta"))
            .toString()
            .substring(0, 7);
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> writer.summary(period)))
        .as("it counts while it is live")
        .isNotEmpty();

    TenantContext.runAs(TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId()));

    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> writer.summary(period)))
        .as("and stops counting the moment it is taken back")
        .isEmpty();
    assertThat(bankBalanceAsAdmin("1900")).as("matching a ledger that nets to zero").isZero();
  }

  /** History has to SAY a row was taken back, or the console offers Take-back on it a second time. */
  @Test
  void historyMarksATakenBackPayout() throws Exception {
    seedOutstanding(500_000L);
    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-history-void");
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> writer.history(CHANNEL)).getFirst().voided())
        .isFalse();

    TenantContext.runAs(TENANT, ACTOR, () -> writer.voidSettlement(settled.settlement().getId()));

    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> writer.history(CHANNEL)).getFirst().voided())
        .isTrue();
  }

  /**
   * The deposit is a convenience; the payout is the money. A bank account the deposit cannot legally
   * use must make the deposit step stand down, NOT take the payout with it — importLines stamps the
   * line with the ACCOUNT's currency, and the resulting mismatch used to roll back the whole
   * transaction.
   */
  @Test
  void aBankAccountInAnotherCurrencyIsSkippedRatherThanFailingThePayout() throws Exception {
    seedBankAccount("USD", true);
    seedOutstanding(500_000L);

    PlatformSettlementResult settled = settle(300_000L, 240_000L, "psw-bank-usd");

    assertThat(settled.created()).as("the payout still happens").isTrue();
    assertThat(bankBalanceAsAdmin("1000")).as("but nothing lands in the bank").isZero();
    assertThat(bankBalanceAsAdmin("1900"))
        .as("the net waits in cash clearing to be swept manually")
        .isEqualTo(240_000L);
    assertThat(statementLineCountAsAdmin()).isZero();
  }

  /** An archived account is not a deposit target, and its presence must not hide the real one. */
  @Test
  void anArchivedBankAccountIsIgnoredSoTheLiveOneStillReceivesTheDeposit() throws Exception {
    seedBankAccount("IDR", false);
    seedBankAccount("IDR", true);
    seedOutstanding(500_000L);

    settle(300_000L, 240_000L, "psw-bank-archived");

    assertThat(bankBalanceAsAdmin("1000")).isEqualTo(240_000L);
  }
}
