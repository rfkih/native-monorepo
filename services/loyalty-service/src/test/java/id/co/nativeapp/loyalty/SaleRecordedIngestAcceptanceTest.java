package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyBalanceChangedSchema;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyRedemptionFlaggedSchema;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.service.MemberService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code SaleRecorded} ingest (ADR 0027): earn math (basis-point floor), points redeem, both in one
 * sale, the overdraft → negative-balance + {@code LoyaltyRedemptionFlagged} path, idempotent
 * re-delivery (the {@code ProcessedEventStore} + ledger backstop), and the monotonic {@code
 * balance_seq} / ABSOLUTE-value contract on {@code LoyaltyBalanceChanged} (decoded straight off the
 * outbox row — Debezium/the relay is not running in this test, so the outbox table is read directly
 * via the admin/BYPASSRLS connection and its Avro payload decoded with {@code libs/events
 * AvroSerde}).
 */
@SpringBootTest
class SaleRecordedIngestAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "22222222-3333-4444-5555-666666666666";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MemberService memberService;
  @Autowired private id.co.nativeapp.loyalty.earnrule.service.EarnRuleService earnRuleService;

  @Test
  void earnAppliesTheBasisPointFloorAndBalanceSeqIsMonotonicAndAbsolute() throws Exception {
    UUID memberId = enroll("0811-0000-0001");
    createEarnRule(333L, null); // 3.33% (333 bp), no floor

    // base = 10,007 minor units; points = floor(10007 * 333 / 10000) = floor(333.2331) = 333.
    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 10_007L, "IDR", 10_007L, 0L, memberId, null, null, null, null);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(333L));

    // balance_seq bumped from 0 (ENROLLED) to 1 (EARNED); LoyaltyBalanceChanged carries the
    // ABSOLUTE resulting balance, not a delta.
    GenericRecord latestEvent = latestOutboxEventFor("LoyaltyBalanceChanged", memberId);
    assertThat((Long) latestEvent.get("points_balance")).isEqualTo(333L);
    assertThat((Long) latestEvent.get("balance_seq")).isEqualTo(1L);
    assertThat(latestEvent.get("reason").toString()).isEqualTo("EARNED");
  }

  @Test
  void redeemAppliesANegativeDeltaAndWritesAReedemLedgerEntry() throws Exception {
    UUID memberId = enroll("0811-0000-0002");
    grantPoints(memberId, 500L);

    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 5_000L, "IDR", 5_000L, 0L, memberId, 200L, 2_000L, null, null);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(300L));

    assertThat(ledgerEntryCount(memberId, "REDEEM")).isEqualTo(1);
  }

  @Test
  void aSaleThatBothRedeemsAndEarnsAppliesBothInOneTransaction() throws Exception {
    UUID memberId = enroll("0811-0000-0003");
    grantPoints(memberId, 1_000L);
    createEarnRule(100L, null); // 1%

    // Redeem 400 points (worth 4,000 minor). Base for earn = subtotal(10,000) - discount(0) -
    // redeemedMinor(4,000) = 6,000; points = floor(6000*100/10000) = 60.
    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 10_000L, "IDR", 10_000L, 0L, memberId, 400L, 4_000L, null, null);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);

    // 1000 - 400 (redeem) + 60 (earn) = 660.
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(660L));
    assertThat(ledgerEntryCount(memberId, "REDEEM")).isEqualTo(1);
    assertThat(ledgerEntryCount(memberId, "EARN")).isEqualTo(1);
  }

  @Test
  void redeemingMoreThanTheBalanceGoesNegativeAndFlagsAnOverdraft() throws Exception {
    UUID memberId = enroll("0811-0000-0004");
    grantPoints(memberId, 100L);

    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 5_000L, "IDR", 5_000L, 0L, memberId, 150L, 1_500L, null, null);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(-50L));

    GenericRecord flagged = latestOutboxEventForType("LoyaltyRedemptionFlagged", TENANT);
    assertThat(flagged.get("member_id").toString()).isEqualTo(memberId.toString());
    assertThat((Long) flagged.get("shortfall_points")).isEqualTo(50L);
    assertThat(flagged.get("gift_card_id")).isNull();
  }

  @Test
  void redeliveringTheSameSaleRecordedEventIsIdempotent() throws Exception {
    UUID memberId = enroll("0811-0000-0005");
    grantPoints(memberId, 500L);

    UUID saleId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 5_000L, "IDR", 5_000L, 0L, memberId, 100L, 1_000L, null, null);

    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(400L));

    // Re-deliver the SAME event id.
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), eventId, sale);
    // Give the redelivery a moment to (not) apply, then assert the balance is UNCHANGED and only
    // one ledger row exists.
    await()
        .pollDelay(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(pointsBalance(memberId)).isEqualTo(400L);
              assertThat(ledgerEntryCount(memberId, "REDEEM")).isEqualTo(1);
            });
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private UUID enroll(String phone) throws Exception {
    MemberResponse response =
        TenantContext.callAs(
            TENANT, ACTOR, () -> memberService.enroll(new EnrollMemberRequest(phone, "Test Member")));
    return response.id();
  }

  private void createEarnRule(long pointsPerMinorBp, Long minSaleMinor) throws Exception {
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            earnRuleService.create(
                new id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest(
                    pointsPerMinorBp, minSaleMinor, "ILLUSTRATIVE_PLACEHOLDER", "test", null, null)));
  }

  /** Directly grants a starting balance via a raw admin UPDATE (test setup shortcut). */
  private void grantPoints(UUID memberId, long points) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE loyalty_member SET points_balance = ?, balance_seq = balance_seq + 1"
                    + " WHERE id = ?")) {
      ps.setLong(1, points);
      ps.setObject(2, memberId);
      ps.executeUpdate();
    }
  }

  private long pointsBalance(UUID memberId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT points_balance FROM loyalty_member WHERE id = ?")) {
      ps.setObject(1, memberId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private int ledgerEntryCount(UUID memberId, String entryType) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COUNT(*) FROM loyalty_ledger_entry WHERE member_id = ? AND entry_type = ?")) {
      ps.setObject(1, memberId);
      ps.setString(2, entryType);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** Reads the MOST RECENT {@code LoyaltyBalanceChanged} outbox row for the given member (aggregate_id). */
  private GenericRecord latestOutboxEventFor(String eventType, UUID memberId) throws Exception {
    return latestOutboxRow(eventType, memberId.toString(), LoyaltyBalanceChangedSchema.schema());
  }

  /** Reads the MOST RECENT event of {@code eventType} for the tenant, keyed by company_id only. */
  private GenericRecord latestOutboxEventForType(String eventType, String companyId)
      throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = ? AND company_id = ?::uuid"
                    + " ORDER BY occurred_at DESC LIMIT 1")) {
      ps.setString(1, eventType);
      ps.setString(2, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        byte[] payload = rs.getBytes(1);
        return AvroSerde.deserialize(payload, LoyaltyRedemptionFlaggedSchema.schema());
      }
    }
  }

  private GenericRecord latestOutboxRow(
      String eventType, String aggregateId, org.apache.avro.Schema schema) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = ? AND aggregate_id = ?"
                    + " ORDER BY occurred_at DESC LIMIT 1")) {
      ps.setString(1, eventType);
      ps.setString(2, aggregateId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        byte[] payload = rs.getBytes(1);
        return AvroSerde.deserialize(payload, schema);
      }
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
