package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.loyalty.giftcard.domain.GiftCardCodeGenerator;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCardNotFoundException;
import id.co.nativeapp.loyalty.giftcard.dto.GiftCardResponse;
import id.co.nativeapp.loyalty.giftcard.service.GiftCardReader;
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
 * {@code GiftCardSold} ingest (card creation + deterministic code derivation + a {@code LOAD}
 * ledger entry) and gift-card redemption via {@code SaleRecorded} (DEPLETED exactly at zero, an
 * overdraft flag on a negative result) — ADR 0027.
 */
@SpringBootTest
class GiftCardIngestAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "33333333-4444-5555-6666-777777777777";
  private static final UUID BUSINESS = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private GiftCardReader giftCardReader;

  @Test
  void giftCardSoldCreatesTheCardWithADerivedCodeAndALoadEntry() throws Exception {
    UUID giftCardId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GenericRecord event = EventFixtures.giftCardSold(giftCardId, TENANT, BUSINESS, 100_000L, "IDR");
    EventFixtures.publishGiftCardSold(KAFKA.getBootstrapServers(), eventId, event);

    String expectedCode = GiftCardCodeGenerator.deriveCode(giftCardId);

    // Awaitility's untilAsserted only auto-retries on AssertionError; the card does not exist yet
    // on the first few polls (async Kafka consumption), so the lookup throws
    // GiftCardNotFoundException — explicitly ignored so the poll retries instead of failing fast.
    await()
        .atMost(Duration.ofSeconds(30))
        .ignoreException(GiftCardNotFoundException.class)
        .untilAsserted(
            () -> {
              GiftCardResponse response =
                  TenantContext.callAs(
                      TENANT, "actor", () -> giftCardReader.lookupByCode(expectedCode));
              assertThat(response.id()).isEqualTo(giftCardId);
              assertThat(response.state()).isEqualTo("ACTIVE");
              assertThat(response.balanceMinor()).isEqualTo(100_000L);
              assertThat(response.currency()).isEqualTo("IDR");
            });

    assertThat(giftCardLedgerEntryCount(giftCardId, "LOAD")).isEqualTo(1);
  }

  @Test
  void redeemingTheFullBalanceDepletesTheCard() throws Exception {
    UUID giftCardId = UUID.randomUUID();
    EventFixtures.publishGiftCardSold(
        KAFKA.getBootstrapServers(),
        UUID.randomUUID(),
        EventFixtures.giftCardSold(giftCardId, TENANT, BUSINESS, 50_000L, "IDR"));

    String code = GiftCardCodeGenerator.deriveCode(giftCardId);
    await()
        .atMost(Duration.ofSeconds(30))
        .ignoreException(GiftCardNotFoundException.class)
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(TENANT, "actor", () -> giftCardReader.lookupByCode(code))
                            .balanceMinor())
                    .isEqualTo(50_000L));

    UUID saleId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 50_000L, "IDR", 50_000L, 0L, null, null, null, giftCardId, 50_000L);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), UUID.randomUUID(), sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              GiftCardResponse response =
                  TenantContext.callAs(TENANT, "actor", () -> giftCardReader.lookupByCode(code));
              assertThat(response.balanceMinor()).isZero();
              assertThat(response.state()).isEqualTo("DEPLETED");
            });
  }

  @Test
  void redeemingMoreThanTheBalanceStaysActiveAndFlagsAnOverdraft() throws Exception {
    UUID giftCardId = UUID.randomUUID();
    EventFixtures.publishGiftCardSold(
        KAFKA.getBootstrapServers(),
        UUID.randomUUID(),
        EventFixtures.giftCardSold(giftCardId, TENANT, BUSINESS, 10_000L, "IDR"));

    String code = GiftCardCodeGenerator.deriveCode(giftCardId);
    await()
        .atMost(Duration.ofSeconds(30))
        .ignoreException(GiftCardNotFoundException.class)
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(TENANT, "actor", () -> giftCardReader.lookupByCode(code))
                            .balanceMinor())
                    .isEqualTo(10_000L));

    UUID saleId = UUID.randomUUID();
    GenericRecord sale =
        EventFixtures.saleRecorded(
            saleId, TENANT, BUSINESS, 15_000L, "IDR", 15_000L, 0L, null, null, null, giftCardId, 15_000L);
    EventFixtures.publishSaleRecorded(KAFKA.getBootstrapServers(), UUID.randomUUID(), sale);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              GiftCardResponse response =
                  TenantContext.callAs(TENANT, "actor", () -> giftCardReader.lookupByCode(code));
              assertThat(response.balanceMinor()).isEqualTo(-5_000L);
              // ADR 0027: even a negative balance stays ACTIVE, not DEPLETED.
              assertThat(response.state()).isEqualTo("ACTIVE");
            });
  }

  private int giftCardLedgerEntryCount(UUID giftCardId, String entryType) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COUNT(*) FROM gift_card_ledger_entry WHERE gift_card_id = ? AND"
                    + " entry_type = ?")) {
      ps.setObject(1, giftCardId);
      ps.setString(2, entryType);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
