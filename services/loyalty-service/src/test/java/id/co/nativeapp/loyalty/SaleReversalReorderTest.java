package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.loyalty.ingest.service.GiftCardSoldWriter;
import id.co.nativeapp.loyalty.ingest.service.SaleIngestWriter;
import id.co.nativeapp.loyalty.ingest.service.SaleReversalWriter;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.service.MemberService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * QA sweep 2026-08-05 CRITICAL regression: {@code SaleVoided}/{@code SaleRefunded} and {@code
 * SaleRecorded} travel on SEPARATE topics, so a reversal can be consumed BEFORE its sale. The old
 * behavior silently returned (nothing persisted) while {@code processOnce} still claimed the
 * reversal event — the reversal was lost forever and the voided sale later EARNED/kept full credit.
 * Now the reversal PARKS a durable {@code pending_sale_reversal} row and ingest applies it in the
 * same transaction the sale lands in — no window ever exposes a voided sale's credit.
 *
 * <p>Direct writer-level test (the {@code EntitlementProjectionReorderGuardTest} pattern) — no
 * Kafka; the reorder is driven deterministically by calling the writers in the inverted order.
 * Seeding goes through real service beans (the RLS GUC binds via the {@code @Transactional}
 * aspect); verification reads use the admin (BYPASSRLS) JDBC connection, the {@code
 * GiftCardIngestAcceptanceTest} pattern.
 */
@SpringBootTest
class SaleReversalReorderTest extends PostgresRlsTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final UUID BUSINESS = UUID.fromString("77777777-0000-0000-0000-000000000001");
  private static final String ACTOR = "test-actor";

  @Autowired private SaleIngestWriter ingestWriter;
  @Autowired private SaleReversalWriter reversalWriter;
  @Autowired private GiftCardSoldWriter giftCardSoldWriter;
  @Autowired private MemberService memberService;

  @Test
  void reversalBeforeSaleParksAndIngestNetCancelsTheRedeem() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID reversalEventId = UUID.randomUUID();
    UUID memberId = enrollMember("0812 1111 0001");

    // T1: the reversal arrives FIRST (cross-topic reorder). It must claim cleanly AND park —
    // not vanish.
    boolean applied = applyReversal(saleId, reversalEventId, SaleReversalFact.Kind.VOIDED);
    assertThat(applied).isTrue();
    assertThat(scalar("SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?", saleId))
        .isEqualTo(1L);
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND applied_at IS NULL",
                saleId))
        .isEqualTo(1L);
    assertThat(reverseRowCount(saleId)).isZero();

    // T2: the sale finally arrives, redeeming 200 points (balance would sit at -200 forever if
    // the parked reversal were lost). REDEEM + REVERSE must both exist and the balance is 0.
    ingestSaleWithRedeem(saleId, memberId, 200L);

    assertThat(ledgerCount(saleId, "REDEEM")).isEqualTo(1L);
    assertThat(reverseRowCount(saleId)).isEqualTo(1L);
    assertThat(scalar("SELECT points_balance FROM loyalty_member WHERE id = ?", memberId)).isZero();
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND applied_at IS NOT NULL",
                saleId))
        .isEqualTo(1L);
  }

  @Test
  void reversalBeforeSaleNetCancelsAGiftCardRedeemToo() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID giftCardId = UUID.randomUUID();
    seedGiftCard(giftCardId, 100_000L);

    applyReversal(saleId, UUID.randomUUID(), SaleReversalFact.Kind.REFUNDED);

    // The sale arrives redeeming 40_000 off the card — must be net-cancelled atomically.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingestWriter.apply(
                new SaleRecordedFact(
                    UUID.randomUUID(),
                    saleId,
                    TENANT,
                    BUSINESS,
                    40_000L,
                    "IDR",
                    Instant.parse("2026-08-05T03:05:00Z"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    giftCardId,
                    40_000L)));

    assertThat(scalar("SELECT balance_minor FROM gift_card WHERE id = ?", giftCardId))
        .isEqualTo(100_000L);
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM gift_card_ledger_entry WHERE sale_id = ?"
                    + " AND entry_type = 'REVERSE'",
                saleId))
        .isEqualTo(1L);
  }

  @Test
  void secondReversalEventBeforeIngestIsANoOpAndOnlyOneReverseRowEverExists() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID memberId = enrollMember("0812 1111 0002");

    // Void then refund for the SAME sale, both before ingest (full-reversal-only, review S2):
    // the first parks; the second must not create a second parking slot.
    applyReversal(saleId, UUID.randomUUID(), SaleReversalFact.Kind.VOIDED);
    applyReversal(saleId, UUID.randomUUID(), SaleReversalFact.Kind.REFUNDED);
    assertThat(scalar("SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?", saleId))
        .isEqualTo(1L);

    ingestSaleWithRedeem(saleId, memberId, 100L);

    assertThat(reverseRowCount(saleId)).isEqualTo(1L);
    assertThat(scalar("SELECT points_balance FROM loyalty_member WHERE id = ?", memberId)).isZero();
  }

  @Test
  void reversalOfASaleWithNoLoyaltyFactsParksHarmlesslyAndIngestResolvesIt() throws Exception {
    UUID saleId = UUID.randomUUID();

    applyReversal(saleId, UUID.randomUUID(), SaleReversalFact.Kind.VOIDED);

    // The sale arrives carrying NO member and NO gift card — nothing to reverse, but the parked
    // row must be RESOLVED (stamped applied), not left dangling as a false pending signal.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingestWriter.apply(
                new SaleRecordedFact(
                    UUID.randomUUID(),
                    saleId,
                    TENANT,
                    BUSINESS,
                    25_000L,
                    "IDR",
                    Instant.parse("2026-08-05T05:05:00Z"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));

    assertThat(
            scalar(
                "SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?"
                    + " AND applied_at IS NOT NULL",
                saleId))
        .isEqualTo(1L);
    assertThat(reverseRowCount(saleId)).isZero();
  }

  @Test
  void saleThenReversalStillReversesDirectlyWithoutParking() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID memberId = enrollMember("0812 1111 0003");

    ingestSaleWithRedeem(saleId, memberId, 150L);
    applyReversal(saleId, UUID.randomUUID(), SaleReversalFact.Kind.VOIDED);

    assertThat(reverseRowCount(saleId)).isEqualTo(1L);
    assertThat(scalar("SELECT points_balance FROM loyalty_member WHERE id = ?", memberId)).isZero();
    // The ingested path never parks — no pending row at all.
    assertThat(scalar("SELECT COUNT(*) FROM pending_sale_reversal WHERE sale_id = ?", saleId))
        .isZero();
  }

  // ── fixtures (seed via service beans — the aspect binds the RLS GUC) ─────────

  private UUID enrollMember(String phone) throws Exception {
    return TenantContext.callAs(
            TENANT, ACTOR, () -> memberService.enroll(new EnrollMemberRequest(phone, "Reorder")))
        .id();
  }

  private void seedGiftCard(UUID giftCardId, long amountMinor) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            giftCardSoldWriter.apply(
                new GiftCardSoldFact(
                    UUID.randomUUID(),
                    "gcs-" + giftCardId.toString().substring(0, 8),
                    giftCardId,
                    TENANT,
                    BUSINESS,
                    amountMinor,
                    "IDR",
                    Instant.parse("2026-08-01T00:00:00Z"))));
  }

  private boolean applyReversal(UUID saleId, UUID eventId, SaleReversalFact.Kind kind)
      throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            reversalWriter.apply(
                new SaleReversalFact(
                    eventId,
                    kind,
                    saleId,
                    TENANT,
                    BUSINESS,
                    Instant.parse("2026-08-05T02:00:00Z"))));
  }

  private void ingestSaleWithRedeem(UUID saleId, UUID memberId, long redeemPoints)
      throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            ingestWriter.apply(
                new SaleRecordedFact(
                    UUID.randomUUID(),
                    saleId,
                    TENANT,
                    BUSINESS,
                    50_000L,
                    "IDR",
                    Instant.parse("2026-08-05T06:00:00Z"),
                    null,
                    null,
                    memberId,
                    redeemPoints,
                    10_000L,
                    null,
                    null)));
  }

  // ── verification (admin/BYPASSRLS JDBC — the GiftCardIngestAcceptanceTest pattern) ───

  private long reverseRowCount(UUID saleId) throws Exception {
    return ledgerCount(saleId, "REVERSE");
  }

  private long ledgerCount(UUID saleId, String entryType) throws Exception {
    return scalar(
        "SELECT COUNT(*) FROM loyalty_ledger_entry WHERE sale_id = ? AND entry_type = '"
            + entryType
            + "'",
        saleId);
  }

  private long scalar(String sql, Object... params) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps = admin.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
