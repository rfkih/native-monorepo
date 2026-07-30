package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.carwash.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.carwash.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleResult;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleService;
import id.co.nativeapp.carwash.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.carwash.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.carwash.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 (ADR 0027) — the carwash gift-card SALE (mint) write path: {@code POST
 * /api/v1/carwash/gift-cards/sell}, exercised here at the {@link GiftCardSaleService} layer
 * (mirrors {@code OutletAccessGuardTest}'s minimal base — no entitlement gate on this endpoint, so
 * no Kafka/Redis is needed).
 *
 * <p>Covers: the sold row + the decoded {@code GiftCardSold} outbox event; the derived display code
 * matching an INDEPENDENT recomputation of loyalty-service's derivation scheme; an idempotent
 * replay (one row, one event); and {@code OutletAccessGuard} enforcement (cashier w/o assignment at
 * an adopted company → 403).
 */
@SpringBootTest
class GiftCardSaleIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "44444444-4444-4444-4444-444444444401";
  private static final String OWNER_ACTOR = "owner-gc@example.co.id";
  private static final String CASHIER_ACTOR = "cashier-gc@example.co.id";
  private static final UUID OUTLET = UUID.fromString("44444444-4444-4444-4444-444444444402");

  @Autowired private GiftCardSaleService giftCardSaleService;
  @Autowired private UserOutletAssignmentRefService assignmentService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sellingAGiftCardPersistsTheSaleAndEmitsGiftCardSoldWithTheDerivedCode() throws Exception {
    String idemKey = "sell-" + UUID.randomUUID();
    SellGiftCardRequest request = new SellGiftCardRequest(OUTLET, idemKey, 100_000L, "IDR", "CASH");

    GiftCardSaleResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(result.created()).isTrue();
    GiftCardSaleResponse sale = result.sale();
    assertThat(sale.amountMinor()).isEqualTo(100_000L);
    assertThat(sale.currency()).isEqualTo("IDR");
    assertThat(sale.tenderType()).isEqualTo("CASH");
    assertThat(sale.businessId()).isEqualTo(OUTLET);

    assertThat(sale.code()).isEqualTo(recomputeDerivedCode(sale.giftCardId()));

    GenericRecord decoded = readGiftCardSoldAdmin(sale.giftCardId());
    assertThat(decoded.get("gift_card_sale_id").toString()).isEqualTo(sale.giftCardSaleId().toString());
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(sale.giftCardId().toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(decoded.get("business_id").toString()).isEqualTo(OUTLET.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(100_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
  }

  @Test
  void idempotentReplayOfTheSameKeyProducesOneRowAndOneEvent() throws Exception {
    String idemKey = "sell-replay-" + UUID.randomUUID();
    SellGiftCardRequest request = new SellGiftCardRequest(OUTLET, idemKey, 50_000L, "IDR", "CASH");

    GiftCardSaleResult first =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));
    assertThat(first.created()).isTrue();
    UUID cardId = first.sale().giftCardId();

    GiftCardSaleResult replay =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(replay.created()).isFalse();
    assertThat(replay.sale().giftCardId()).isEqualTo(cardId);
    assertThat(countGiftCardSaleRowsAdmin(cardId)).isEqualTo(1);
    assertThat(countGiftCardSoldEventsAdmin(cardId)).isEqualTo(1);
  }

  @Test
  void cashierWithoutOutletAssignmentIsRejectedWith403WhenScopingIsAdopted() throws Exception {
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "other-cashier",
            TENANT,
            OUTLET,
            "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);

    SellGiftCardRequest request =
        new SellGiftCardRequest(OUTLET, "sell-403-" + UUID.randomUUID(), 25_000L, "IDR", "CASH");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> giftCardSaleService.sell(request)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  // -----------------------------------------------------------------------
  // Independent code-derivation recomputation
  // -----------------------------------------------------------------------

  private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private static String recomputeDerivedCode(UUID giftCardId) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2);
    buffer.putLong(giftCardId.getMostSignificantBits());
    buffer.putLong(giftCardId.getLeastSignificantBits());
    byte[] truncated = new byte[10];
    System.arraycopy(buffer.array(), 0, truncated, 0, 10);
    StringBuilder out = new StringBuilder();
    int bitBuffer = 0;
    int bitsLeft = 0;
    for (byte b : truncated) {
      bitBuffer = (bitBuffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        int index = (bitBuffer >> (bitsLeft - 5)) & 0x1F;
        out.append(BASE32_ALPHABET[index]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      int index = (bitBuffer << (5 - bitsLeft)) & 0x1F;
      out.append(BASE32_ALPHABET[index]);
    }
    return out.toString();
  }

  // -----------------------------------------------------------------------
  // Fixtures / helpers
  // -----------------------------------------------------------------------

  private GenericRecord readGiftCardSoldAdmin(UUID cardId) {
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'GiftCardSold' AND aggregate_id = ?"
                + " ORDER BY occurred_at DESC LIMIT 1",
            cardId.toString());
    return AvroSerde.deserialize((byte[]) outboxRow.get("payload"), GiftCardSoldSchema.schema());
  }

  /**
   * {@code gift_card_sale} carries FORCE ROW LEVEL SECURITY — reads over the admin/BYPASSRLS
   * connection (the plain RLS-scoped {@code jdbcTemplate} bean has no tenant GUC bound outside an
   * active {@code @Transactional} call).
   */
  private int countGiftCardSaleRowsAdmin(UUID cardId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM gift_card_sale WHERE gift_card_id = ?")) {
      ps.setObject(1, cardId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countGiftCardSoldEventsAdmin(UUID cardId) {
    // outbox carries NO row-level security (Debezium needs unrestricted read access).
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE event_type = 'GiftCardSold' AND aggregate_id = ?",
        Integer.class,
        cardId.toString());
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
