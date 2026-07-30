package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
 * {@code SaleVoided}/{@code SaleRefunded} REVERSE the sale's earn/redeem entries by {@code
 * sale_id}, full-reversal only (ADR 0027) — a void and a PARTIAL refund both undo the FULL loyalty
 * impact of the original sale (loyalty stores only sale-level facts).
 */
@SpringBootTest
class SaleReversalAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "44444444-5555-6666-7777-888888888888";
  private static final String ACTOR = "owner@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @Autowired private MemberService memberService;
  @Autowired private id.co.nativeapp.loyalty.earnrule.service.EarnRuleService earnRuleService;

  @Test
  void voidingASaleReversesItsEarnedPoints() throws Exception {
    UUID memberId = enroll("0822-1111-0001");

    UUID saleId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 10_000L, "IDR", 10_000L, 0L, memberId, null, null, null,
            null);
    grantEarnRule();
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), UUID.randomUUID(), sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(100L));

    EventFixtures.publishSaleVoided(
        KAFKA.getBootstrapServers(),
        UUID.randomUUID(),
        EventFixtures.saleVoided(saleId, TENANT, BUSINESS, 10_000L, "IDR"));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isZero());

    assertThat(reversalRowCount(saleId)).isEqualTo(1);
  }

  @Test
  void refundingASalePartiallyStillReversesTheFullOriginalRedemption() throws Exception {
    UUID memberId = enroll("0822-1111-0002");
    grantPoints(memberId, 500L);

    UUID saleId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 5_000L, "IDR", 5_000L, 0L, memberId, 200L, 2_000L, null,
            null);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), UUID.randomUUID(), sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(300L));

    // A PARTIAL refund (half the amount) still reverses the FULL 200-point redemption.
    EventFixtures.publishSaleRefunded(
        KAFKA.getBootstrapServers(),
        UUID.randomUUID(),
        EventFixtures.saleRefunded(saleId, TENANT, BUSINESS, 2_500L, "IDR"));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(pointsBalance(memberId)).isEqualTo(500L));
  }

  private UUID enroll(String phone) throws Exception {
    MemberResponse response =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> memberService.enroll(new EnrollMemberRequest(phone, "Test Member")));
    return response.id();
  }

  private void grantEarnRule() throws Exception {
    // 1% flat, no floor.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            earnRuleService.create(
                new id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest(
                    100L, null, "ILLUSTRATIVE_PLACEHOLDER", "test", null, null)));
  }

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

  private int reversalRowCount(UUID saleId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COUNT(*) FROM loyalty_ledger_entry WHERE sale_id = ? AND entry_type ="
                    + " 'REVERSE'")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
