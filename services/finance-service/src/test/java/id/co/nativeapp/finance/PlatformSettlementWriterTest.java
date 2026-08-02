package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.platform.domain.PlatformNetExceedsGrossException;
import id.co.nativeapp.finance.platform.domain.PlatformOverSettlementException;
import id.co.nativeapp.finance.platform.domain.PlatformSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.service.PlatformReceivableAccumulator;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
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
 * accumulator is seeded through {@link PlatformReceivableAccumulator} — the same component the
 * revenue/reversal writers use in production.
 */
@SpringBootTest
class PlatformSettlementWriterTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-cccccccccccc";
  private static final String CHANNEL = "GOFOOD";
  private static final String ACTOR = "platform@integration.co.id";

  @Autowired private PlatformSettlementWriter writer;
  @Autowired private PlatformReceivableAccumulator accumulator;

  private void seedOutstanding(long minor) {
    // accumulate is @Transactional, so the RLS aspect binds the GUC from this scope.
    TenantContext.runAs(
        TENANT, ACTOR, () -> accumulator.accumulate(TENANT, CHANNEL, "IDR", minor, ACTOR));
  }

  private PlatformSettlementResult settle(long gross, long net, String key) throws Exception {
    return TenantContext.callAs(
        TENANT, ACTOR, () -> writer.settle(CHANNEL, gross, net, "IDR", key));
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
}
