package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
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

  /** Card has no mapped fee account, so it must be refused rather than booked to the wrong one. */
  @Test
  void aCardLineIsRefusedUntilItsFeeAccountExists() {
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        writer.settleSources(
                            "BCA",
                            List.of(
                                new SourceLine(SettlementSourceKind.CARD, "TENDER:CARD", 1_000L)),
                            900L,
                            "IDR",
                            "psw-card-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("card");
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
}
