package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance — the {@code member_balance_ref} / {@code gift_card_ref} read models, driven
 * end-to-end through a REAL Kafka broker (ported from carwash-service's {@code
 * LoyaltyRefKafkaConsumeTest}, itself mirroring {@code StaffReadModelConsumeTest}).
 *
 * <p>Publishing {@code LoyaltyBalanceChanged} / {@code GiftCardStateChanged} (the loyalty-service +
 * Debezium shape: raw Avro bytes + the {@code id} header, via {@link EventFixtures}) is consumed by
 * the real {@code @KafkaListener}s into the local read models — proving the WIRE-level path. The
 * deeper idempotency/set-if-newer correctness is proven deterministically in {@link
 * LoyaltyRefConsumerAcceptanceTest}.
 */
@SpringBootTest
class LoyaltyRefKafkaConsumeTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

  @Test
  void publishingLoyaltyBalanceChangedOverKafkaUpsertsTheMemberBalanceRefRow() throws Exception {
    String memberId = UUID.randomUUID().toString();

    EventFixtures.publishLoyaltyBalanceChanged(
        KAFKA.getBootstrapServers(),
        memberId,
        UUID.randomUUID(),
        EventFixtures.loyaltyBalanceChanged(memberId, TENANT_A, 2_500L, 1L, "ENROLLED"));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(memberBalanceAsAdmin(memberId)).isEqualTo(2_500L));
  }

  @Test
  void publishingGiftCardStateChangedOverKafkaUpsertsTheGiftCardRefRow() throws Exception {
    String cardId = UUID.randomUUID().toString();

    EventFixtures.publishGiftCardStateChanged(
        KAFKA.getBootstrapServers(),
        cardId,
        UUID.randomUUID(),
        EventFixtures.giftCardStateChanged(cardId, TENANT_A, "ACTIVE", 60_000L, "IDR", 1L));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(giftCardBalanceAsAdmin(cardId)).isEqualTo(60_000L));
    assertThat(giftCardStateAsAdmin(cardId)).isEqualTo("ACTIVE");
  }

  private Long memberBalanceAsAdmin(String memberId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT points_balance FROM member_balance_ref WHERE member_id = ?::uuid")) {
      ps.setString(1, memberId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getLong(1);
      }
    }
  }

  private Long giftCardBalanceAsAdmin(String cardId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT balance_minor FROM gift_card_ref WHERE gift_card_id = ?::uuid")) {
      ps.setString(1, cardId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getLong(1);
      }
    }
  }

  private String giftCardStateAsAdmin(String cardId) throws Exception {
    try (java.sql.Connection admin = admin();
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT state FROM gift_card_ref WHERE gift_card_id = ?::uuid")) {
      ps.setString(1, cardId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return rs.getString(1);
      }
    }
  }

  private java.sql.Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
