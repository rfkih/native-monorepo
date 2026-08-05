package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.opening.domain.OpeningBalanceAlreadyRecordedException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceIdempotencyKeyConflictException;
import id.co.nativeapp.finance.opening.domain.OpeningBalancePnlAccountException;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceSide;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceVatAccountException;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceLine;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceResult;
import id.co.nativeapp.finance.opening.service.OpeningBalanceWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import id.co.nativeapp.finance.statements.service.BalanceSheetReader;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code OpeningBalanceWriter} matrix (ADR 0037): a balanced opening entry auto-plugged to
 * Opening Balance Equity (3900), the balance sheet reading {@code assets = liabilities + equity},
 * balance-sheet-accounts-only (a REVENUE/EXPENSE or unknown account is rejected), once-only per
 * company, same-key replay, and payload-conflict 409. A fresh tenant per test keeps the once-only
 * {@code company_opening_balance} row from colliding across methods.
 */
@SpringBootTest
class OpeningBalanceWriterTest extends PostgresRlsTestBase {

  private static final String ACTOR = "opening@integration.co.id";
  private final String tenant = UUID.randomUUID().toString();

  @Autowired private OpeningBalanceWriter writer;
  @Autowired private BalanceSheetReader balanceSheetReader;
  @Autowired private Clock clock;

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private String period() {
    return LedgerPosting.periodOf(clock.instant());
  }

  private OpeningBalanceResult record(List<OpeningBalanceLine> lines, String key) throws Exception {
    return TenantContext.callAs(tenant, ACTOR, () -> writer.record(today(), "IDR", lines, key));
  }

  @Test
  void vatControlAccountLinesAreRejectedAndDoNotConsumeTheOnceOnly() throws Exception {
    // 2200 (VAT_OUTPUT) and 1300 (VAT_INPUT) are GL-derived PPN return inputs — an opening
    // control balance there would count as period VAT activity (QA sweep 2026-08-05). Migrated
    // VAT positions belong on generic liability/asset lines.
    assertThatThrownBy(
            () ->
                record(
                    List.of(
                        new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT),
                        new OpeningBalanceLine("2200", 1_000_000L, OpeningBalanceSide.CREDIT)),
                    "ob-vat-out"))
        .isInstanceOf(OpeningBalanceVatAccountException.class);
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("1300", 400_000L, OpeningBalanceSide.DEBIT)),
                    "ob-vat-in"))
        .isInstanceOf(OpeningBalanceVatAccountException.class);

    // Nothing persisted — the once-only slot is still free and a clean submission succeeds.
    OpeningBalanceResult ok =
        record(
            List.of(new OpeningBalanceLine("1900", 1_000_000L, OpeningBalanceSide.DEBIT)),
            "ob-vat-clean");
    assertThat(ok.created()).isTrue();
  }

  @Test
  void recordPostsBalancedEntryAutoPluggingToOpeningBalanceEquity() throws Exception {
    OpeningBalanceResult result =
        record(
            List.of(
                new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT), // cash
                new OpeningBalanceLine("1100", 2_000_000L, OpeningBalanceSide.DEBIT), // inventory
                new OpeningBalanceLine("2700", 1_000_000L, OpeningBalanceSide.CREDIT), // loan
                new OpeningBalanceLine("3000", 4_000_000L, OpeningBalanceSide.CREDIT)), // capital
            "ob-key-1");

    assertThat(result.created()).isTrue();
    assertThat(result.openingBalance().getPlugMinor()).isEqualTo(2_000_000L); // Cr OBE residual
    assertThat(result.openingBalance().getTotalMinor()).isEqualTo(7_000_000L);

    Map<String, long[]> legs = entryLegsAsAdmin(result.openingBalance().getOpeningEntryId());
    assertThat(legs.get("1900")[0]).isEqualTo(5_000_000L);
    assertThat(legs.get("1100")[0]).isEqualTo(2_000_000L);
    assertThat(legs.get("2700")[1]).isEqualTo(1_000_000L);
    assertThat(legs.get("3000")[1]).isEqualTo(4_000_000L);
    assertThat(legs.get("3900")[1]).isEqualTo(2_000_000L); // the auto-plug
    assertThat(legs).doesNotContainKey("1590"); // no depreciation, no phantom accounts

    BalanceSheetResponse bs =
        TenantContext.callAs(tenant, ACTOR, () -> balanceSheetReader.read(period()).orElseThrow());
    assertThat(bs.totalAssetsMinor()).isEqualTo(7_000_000L);
    assertThat(bs.totalLiabilitiesMinor()).isEqualTo(1_000_000L);
    assertThat(bs.totalEquityMinor()).isEqualTo(6_000_000L);
    assertThat(bs.totalAssetsMinor()).isEqualTo(bs.totalLiabilitiesAndEquityMinor());
  }

  @Test
  void residualPlugsAsADebitWhenCreditsExceedDebits() throws Exception {
    OpeningBalanceResult result =
        record(
            List.of(
                new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT),
                new OpeningBalanceLine("3000", 6_000_000L, OpeningBalanceSide.CREDIT)),
            "ob-key-debit-plug");

    assertThat(result.openingBalance().getPlugMinor()).isEqualTo(-1_000_000L);
    Map<String, long[]> legs = entryLegsAsAdmin(result.openingBalance().getOpeningEntryId());
    assertThat(legs.get("3900")[0]).isEqualTo(1_000_000L); // Dr OBE
  }

  @Test
  void rejectsARevenueAccount() {
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("4000", 1_000_000L, OpeningBalanceSide.CREDIT)),
                    "ob-key-pnl"))
        .isInstanceOf(OpeningBalancePnlAccountException.class);
  }

  @Test
  void rejectsAnUnknownAccount() {
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("7777", 1_000_000L, OpeningBalanceSide.DEBIT)),
                    "ob-key-unknown"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onceOnlyRejectsASecondRecordUnderADifferentKey() throws Exception {
    record(
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT)), "ob-once-1");
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("1900", 3_000_000L, OpeningBalanceSide.DEBIT)),
                    "ob-once-2"))
        .isInstanceOf(OpeningBalanceAlreadyRecordedException.class);
  }

  @Test
  void sameKeyReplayReturnsTheOriginalWithoutPostingAgain() throws Exception {
    List<OpeningBalanceLine> lines =
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT));
    OpeningBalanceResult first = record(lines, "ob-replay");

    OpeningBalanceResult replay = record(lines, "ob-replay");

    assertThat(replay.created()).isFalse();
    assertThat(replay.openingBalance().getId()).isEqualTo(first.openingBalance().getId());
    assertThat(
            TenantContext.callAs(
                tenant, ACTOR, () -> writer.current().orElseThrow().getOpeningEntryId()))
        .isEqualTo(first.openingBalance().getOpeningEntryId());
  }

  @Test
  void replayedKeyWithADifferentPayloadConflicts() throws Exception {
    record(
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT)),
        "ob-conflict");
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("1900", 9_000_000L, OpeningBalanceSide.DEBIT)),
                    "ob-conflict"))
        .isInstanceOf(OpeningBalanceIdempotencyKeyConflictException.class);
  }

  @Test
  void replayedKeyWithSameTotalButDifferentLinesConflicts() throws Exception {
    // review M1: a different line-set that shares the same total/currency/as-of must NOT masquerade
    // as the same payload — the SHA-256 line fingerprint distinguishes them.
    record(List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT)), "ob-fp");
    assertThatThrownBy(
            () ->
                record(
                    List.of(new OpeningBalanceLine("1100", 5_000_000L, OpeningBalanceSide.DEBIT)),
                    "ob-fp"))
        .isInstanceOf(OpeningBalanceIdempotencyKeyConflictException.class);
  }

  @Test
  void rlsIsolatesOpeningBalancesFromAnotherTenant() throws Exception {
    record(
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT)), "ob-rls-a");
    String tenantB = UUID.randomUUID().toString();
    assertThat(TenantContext.callAs(tenantB, ACTOR, () -> writer.current())).isEmpty();
    // once-only is per company, so tenant B records its own without a conflict.
    OpeningBalanceResult b =
        TenantContext.callAs(
            tenantB,
            ACTOR,
            () ->
                writer.record(
                    today(),
                    "IDR",
                    List.of(new OpeningBalanceLine("1900", 1_000_000L, OpeningBalanceSide.DEBIT)),
                    "ob-rls-b"));
    assertThat(b.created()).isTrue();
  }

  /** account_code → [debit, credit] for the entry's lines, read over the BYPASSRLS admin. */
  private Map<String, long[]> entryLegsAsAdmin(UUID entryId) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line"
                    + " WHERE entry_id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        Map<String, long[]> legs = new HashMap<>();
        while (rs.next()) {
          legs.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
        }
        return legs;
      }
    }
  }
}
